package dev.faraway.k7q4m9.worldgen;

import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Phase;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

/** Tree-bearing sections from live chunks and the current world's completed chunk saves. */
public final class ExistingTreeBlocks {
    private record Chunk(int minY,LevelChunkSection[] sections) {}
    private final Map<Long,Chunk> chunks;

    private ExistingTreeBlocks(Map<Long,Chunk> chunks) { this.chunks=chunks; }

    /** A completed snapshot with zero tree sections still overrides simulated trees. */
    public static final class Storage {
        private static final int CACHE_LIMIT=512;
        private static final long MISS_TTL_NS=5_000_000_000L;
        private static final long HIT_TTL_NS=30_000_000_000L;
        private record Read(CompletableFuture<Optional<Chunk>> future,long created) {}
        private final ServerLevel level;
        private final PalettedContainerFactory containers;
        private final LinkedHashMap<Long,Read> cache=new LinkedHashMap<>(64,.75f,true);
        private final AtomicBoolean readFailureLogged=new AtomicBoolean();

        public Storage(WorldDefinition world) {
            level=world.sourceLevel();
            containers=PalettedContainerFactory.create(world.registries());
        }

        public ExistingTreeBlocks capture(int minX,int minZ,int maxX,int maxZ,BooleanSupplier cancelled) {
            Map<Long,Chunk> chunks;
            try(var profile=WorldgenProfiler.scope(Phase.TREE_LIVE_READ)) {
                chunks=level.getServer().submit(() -> captureLoaded(minX,minZ,maxX,maxZ,cancelled)).join();
            } catch(CompletionException failure) {
                if(failure.getCause() instanceof CancellationException stopped) throw stopped;
                throw failure;
            }
            var reads=new HashMap<Long,CompletableFuture<Optional<Chunk>>>();
            for(int z=minZ>>4;z<=maxZ>>4;z++) for(int x=minX>>4;x<=maxX>>4;x++) {
                check(cancelled);
                long key=ChunkPos.pack(x,z);
                if(chunks.containsKey(key)) {
                    synchronized(this) { cache.remove(key); }
                } else reads.put(key,read(new ChunkPos(x,z)));
            }
            if(!reads.isEmpty()) try(var profile=WorldgenProfiler.scope(Phase.TREE_DISK_READ)) {
                for(var entry:reads.entrySet()) {
                    check(cancelled);
                    entry.getValue().join().ifPresent(chunk -> chunks.put(entry.getKey(),chunk));
                }
            }
            return new ExistingTreeBlocks(chunks);
        }

        private Map<Long,Chunk> captureLoaded(int minX,int minZ,int maxX,int maxZ,BooleanSupplier cancelled) {
            var chunks=new HashMap<Long,Chunk>();
            for(int z=minZ>>4;z<=maxZ>>4;z++) for(int x=minX>>4;x<=maxX>>4;x++) {
                check(cancelled);
                var chunk=level.getChunkSource().getChunkNow(x,z);
                if(chunk==null) continue;
                var sections=new LevelChunkSection[chunk.getSections().length];
                for(int i=0;i<sections.length;i++) {
                    var section=chunk.getSection(i);
                    if(hasTrees(section)) sections[i]=section.copy();
                }
                chunks.put(ChunkPos.pack(x,z),new Chunk(chunk.getMinY(),sections));
            }
            return chunks;
        }

        private synchronized CompletableFuture<Optional<Chunk>> read(ChunkPos pos) {
            long key=pos.pack(),now=System.nanoTime();
            var old=cache.get(key);
            if(old!=null) {
                if(!old.future().isDone()) return old.future();
                long ttl=old.future().getNow(Optional.empty()).isPresent()?HIT_TTL_NS:MISS_TTL_NS;
                if(now-old.created()<ttl) return old.future();
            }
            var future=level.getChunkSource().chunkMap.read(pos)
                .thenApplyAsync(tag -> tag.flatMap(saved -> decode(pos,saved)),Util.backgroundExecutor().forName("FarAway saved trees"))
                .exceptionally(failure -> {
                    if(readFailureLogged.compareAndSet(false,true))
                        dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.warn("FarAway could not read stored tree blocks at {}",pos,failure);
                    return Optional.empty();
                });
            cache.put(key,new Read(future,now));
            var it=cache.entrySet().iterator();
            while(cache.size()>CACHE_LIMIT && it.hasNext()) if(it.next().getValue().future().isDone()) it.remove();
            return future;
        }

        private Optional<Chunk> decode(ChunkPos pos,CompoundTag tag) {
            if(NbtUtils.getDataVersion(tag)!=SharedConstants.getCurrentVersion().dataVersion().version()
                || tag.read("Status",ChunkStatus.CODEC).orElse(ChunkStatus.EMPTY)!=ChunkStatus.FULL) return Optional.empty();
            var data=SerializableChunkData.parse(level,containers,tag);
            if(data==null || !data.chunkPos().equals(pos)) return Optional.empty();
            var sections=new LevelChunkSection[level.getSectionsCount()];
            int minSection=Math.floorDiv(level.getMinY(),16);
            for(var section:data.sectionData()) {
                int index=section.y()-minSection;
                var blocks=section.chunkSection();
                if(index>=0 && index<sections.length && hasTrees(blocks)) sections[index]=blocks;
            }
            return Optional.of(new Chunk(level.getMinY(),sections));
        }

        public synchronized void clear() { cache.clear(); }
    }

    private static boolean hasTrees(LevelChunkSection section) {
        return section!=null && !section.hasOnlyAir() && section.maybeHas(ExistingTreeBlocks::isTree);
    }

    public boolean contains(BlockPos pos) { return chunks.containsKey(ChunkPos.pack(pos.getX()>>4,pos.getZ()>>4)); }

    public boolean covers(int minX,int minZ,int maxX,int maxZ) {
        for(int z=minZ>>4;z<=maxZ>>4;z++) for(int x=minX>>4;x<=maxX>>4;x++)
            if(!chunks.containsKey(ChunkPos.pack(x,z))) return false;
        return true;
    }

    public void addTo(Map<BlockPos,BlockState> output,int minX,int minZ,int maxX,int maxZ,BooleanSupplier cancelled) {
        for(var item:chunks.entrySet()) {
            var pos=ChunkPos.unpack(item.getKey());
            var chunk=item.getValue();
            for(int i=0;i<chunk.sections().length;i++) {
                check(cancelled);
                var section=chunk.sections()[i];
                if(section==null) continue;
                int baseY=chunk.minY()+i*16;
                for(int z=Math.max(minZ,pos.getMinBlockZ());z<=Math.min(maxZ,pos.getMaxBlockZ());z++)
                    for(int x=Math.max(minX,pos.getMinBlockX());x<=Math.min(maxX,pos.getMaxBlockX());x++)
                        for(int y=0;y<16;y++) {
                            var state=section.getBlockState(x&15,y,z&15);
                            if(isTree(state)) output.put(new BlockPos(x,baseY+y,z),state);
                        }
            }
        }
    }

    private static boolean isTree(BlockState state) { return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES); }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean()) throw new CancellationException(); }
}
