package dev.faraway.k7q4m9.worldgen;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import dev.faraway.k7q4m9.terrain.TileKey;
import dev.faraway.k7q4m9.diagnostics.WorldgenCacheProfile.Kind;
import dev.faraway.k7q4m9.diagnostics.WorldgenCacheProfile.Task;

/** World-session structure metadata, shared by tile workers. Discovery never places blocks. */
public final class StructureIndex {
    public record Snapshot(List<StructureSampler.Found> starts,Set<TileKey> patches) {}
    private final WorldDefinition world;
    private final BooleanSupplier closed;
    private final StructureSampler.CandidateCache candidates=new StructureSampler.CandidateCache();
    private final ThreadLocal<StructureSampler> samplers;
    private record Region(int x,int z) {
        @Override public String toString() { return "region "+x+","+z+" (256 blocks)"; }
    }
    private final LinkedHashMap<Region,Task<Snapshot>> regions=new LinkedHashMap<>(4,.75f,true);
    public StructureIndex(WorldDefinition world,BooleanSupplier closed) {
        this.world=world; this.closed=closed;
        samplers=ThreadLocal.withInitial(() -> new StructureSampler(world,candidates));
    }
    public Set<TileKey> patches(TileKey tile) {
        if(tile.blocks()<=256) return get(tile).patches();
        var result=new HashSet<TileKey>();
        for(int z=Math.floorDiv(tile.originZ(),256);z<=Math.floorDiv(tile.originZ()+tile.blocks()-1,256);z++)
            for(int x=Math.floorDiv(tile.originX(),256);x<=Math.floorDiv(tile.originX()+tile.blocks()-1,256);x++)
                result.addAll(get(new TileKey(x,z,8)).patches());
        return result;
    }

    /** Searches a 256-block cell with a 1024-block structure start margin. */
    public Snapshot get(TileKey tile) {
        if(tile.blocks()>256) throw new IllegalArgumentException("Structure index is limited to LOD 0-3");
        int gx=Math.floorDiv(tile.originX(),256),gz=Math.floorDiv(tile.originZ(),256);
        var key=new Region(gx,gz);
        Task<Snapshot> task;
        synchronized(regions) {
            task=regions.computeIfAbsent(key,ignored -> new Task<>(Kind.STRUCTURE_INDEX,() -> {
                long start=System.nanoTime();
                // The cell's farthest corner plus the 32-block patch margin fits in
                // 256 blocks. StructureSampler adds a separate 1024-block start margin.
                var starts=samplers.get().discover(gx*256+128,gz*256+128,256,closed);
                var patches=new HashSet<TileKey>();
                for(var found:starts) {
                    var structureStart=found.start();
                    // Jigsaw structures have no structure-level afterPlace; their
                    // pieces are placed only in chunks intersecting a piece. Keep a
                    // one-chunk margin for template/processor writes at boundaries.
                    // Other structures retain the wider original bounds because
                    // their afterPlace or processors can modify additional terrain.
                    if(structureStart.getStructure() instanceof net.minecraft.world.level.levelgen.structure.structures.JigsawStructure) {
                        for(var piece:structureStart.getPieces()) addPatches(patches,piece.getBoundingBox(),16);
                    } else addPatches(patches,structureStart.getBoundingBox(),32);
                }
                dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.info("FarAway structure index region={},{} totalMs={} starts={} patches={}",gx,gz,(System.nanoTime()-start)/1_000_000,starts.size(),patches.size());
                return new Snapshot(starts,Set.copyOf(patches));
            }));
            // Retain only recent metadata regions; callers keep their own in-flight snapshot.
            while(regions.size()>32) regions.remove(regions.keySet().iterator().next());
        }
        try { return task.read(key); }
        catch(InterruptedException stopped) { Thread.currentThread().interrupt(); throw new CancellationException(); }
        catch(ExecutionException failure) {
            synchronized(regions) { regions.remove(key,task); }
            if(failure.getCause() instanceof CancellationException cancelled) throw cancelled;
            throw new IllegalStateException("Structure discovery failed",failure.getCause());
        }
    }
    static void addPatches(Set<TileKey> patches,net.minecraft.world.level.levelgen.structure.BoundingBox bounds,int margin) {
        for(int pz=Math.floorDiv(bounds.minZ()-margin,32);pz<=Math.floorDiv(bounds.maxZ()+margin,32);pz++)
            for(int px=Math.floorDiv(bounds.minX()-margin,32);px<=Math.floorDiv(bounds.maxX()+margin,32);px++)
                patches.add(new TileKey(px,pz,1));
    }
}
