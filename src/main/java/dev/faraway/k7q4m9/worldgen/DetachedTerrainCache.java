package dev.faraway.k7q4m9.worldgen;

import dev.faraway.k7q4m9.diagnostics.WorldgenCacheProfile.Future;
import dev.faraway.k7q4m9.diagnostics.WorldgenCacheProfile.Kind;

import java.util.LinkedHashMap;
import java.util.concurrent.*;
import java.util.function.Supplier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.ProtoChunkTicks;

/** Immutable section snapshots; each caller receives a private mutable chunk. */
public final class DetachedTerrainCache {
    private static final long ESTIMATED_BYTES=512L*1024;
    private static final java.util.EnumSet<Heightmap.Types> MAPS=java.util.EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG,Heightmap.Types.WORLD_SURFACE_WG,
        Heightmap.Types.MOTION_BLOCKING,Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,Heightmap.Types.OCEAN_FLOOR,Heightmap.Types.WORLD_SURFACE);
    private record Snapshot(LevelChunkSection[] sections,java.util.Map<Heightmap.Types,long[]> heightmaps) {}
    private record Key(long chunk,boolean adapted) {
        @Override public String toString() { return "chunk "+ChunkPos.unpack(chunk)+(adapted?" adapted":""); }
    }
    private final LinkedHashMap<Key,Future<Snapshot>> entries=new LinkedHashMap<>(64,.75f,true);
    private final java.util.HashMap<Key,Long> lastUsed=new java.util.HashMap<>();
    private final java.util.concurrent.atomic.LongAdder hits=new java.util.concurrent.atomic.LongAdder(),misses=new java.util.concurrent.atomic.LongAdder();
    public ProtoChunk get(ChunkPos pos,WorldDefinition world,PalettedContainerFactory containers,long limit,boolean adapted,Supplier<ProtoChunk> build) {
        var key=new Key(pos.pack(),adapted); Future<Snapshot> future; boolean owner=false;
        synchronized(this) {
            lastUsed.put(key,System.nanoTime());
            future=entries.get(key);
            if(future==null) { future=new Future<>(); entries.put(key,future); owner=true; misses.increment(); }
            else hits.increment();
        }
        if(owner) {
            try {
                Snapshot snapshot;
                try(var guard=CachePause.producing();var profile=future.build(Kind.DETACHED_TERRAIN)) {
                    var built=build.get();
                    var source=built.getSections();
                    var sections=new LevelChunkSection[source.length];
                    for(int i=0;i<source.length;i++) sections[i]=source[i].copy();
                    Heightmap.primeHeightmaps(built,MAPS);
                    var heightmaps=new java.util.EnumMap<Heightmap.Types,long[]>(Heightmap.Types.class);
                    for(var type:MAPS) heightmaps.put(type,built.getOrCreateHeightmapUnprimed(type).getRawData().clone());
                    snapshot=new Snapshot(sections,heightmaps);
                }
                future.complete(snapshot);
                synchronized(this) {
                    var it=entries.entrySet().iterator();
                    while((long)entries.size()*ESTIMATED_BYTES>limit && it.hasNext()) {
                        var e=it.next(); if(e.getKey().equals(key) || !e.getValue().isDone()) continue; lastUsed.remove(e.getKey()); it.remove();
                    }
                }
            } catch(Throwable error) {
                synchronized(this) { entries.remove(key,future); lastUsed.remove(key); }
                future.completeExceptionally(error);
            }
        }
        Snapshot snapshot;
        try { snapshot=owner?future.join():future.read(Kind.DETACHED_TERRAIN,key); }
        catch(CompletionException error) {
            if(error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw error;
        }
        var sections=new LevelChunkSection[snapshot.sections().length];
        for(int i=0;i<sections.length;i++) sections[i]=snapshot.sections()[i].copy();
        var result=new ProtoChunk(pos,UpgradeData.EMPTY,sections,new ProtoChunkTicks<>(),new ProtoChunkTicks<>(),world.heights(),containers,null);
        result.setPersistedStatus(ChunkStatus.TERRAIN);
        for(var type:MAPS) result.setHeightmap(type,snapshot.heightmaps().get(type));
        return result;
    }
    public synchronized void clear() { entries.clear(); lastUsed.clear(); }
    public synchronized void expire(long now) {
        var it=entries.entrySet().iterator();
        while(it.hasNext()) { var item=it.next(); if(item.getValue().isDone() && now-lastUsed.getOrDefault(item.getKey(),now)>=10_000_000_000L) { lastUsed.remove(item.getKey()); it.remove(); } }
    }
    public synchronized String status() { return "terrainCacheHits="+hits.sum()+" terrainCacheMisses="+misses.sum()+" terrainCacheEntries="+entries.size(); }
}
