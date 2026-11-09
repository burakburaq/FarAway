package dev.faraway.k7q4m9.worldgen;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;

/** A detached feature region whose outer metadata dependencies can be backed by lazy terrain. */
abstract class ScratchWorldGenRegion extends WorldGenRegion {
    private static final ChunkStep FEATURES=
        ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);
    private final ChunkPos center;
    private final BiFunction<Integer,Integer,ProtoChunk> terrain;
    private final boolean onDemand;

    ScratchWorldGenRegion(WorldDefinition world,ChunkPos center,BiFunction<Integer,Integer,ProtoChunk> terrain,boolean onDemand) {
        super(world.sourceLevel(),holders(center,terrain,onDemand),FEATURES,terrain.apply(center.x(),center.z()));
        this.center=center;
        this.terrain=terrain;
        this.onDemand=onDemand;
    }

    private static StaticCache2D<GenerationChunkHolder> holders(ChunkPos center,BiFunction<Integer,Integer,ProtoChunk> terrain,boolean onDemand) {
        return StaticCache2D.create(center.x(),center.z(),FEATURES.directDependencies().size()-1,(x,z) -> new GenerationChunkHolder(new ChunkPos(x,z)) {
            @Override public ChunkAccess getChunkIfPresentUnchecked(ChunkStatus status) {
                // WorldGenRegion's eager map accepts null. Actual reads are resolved below.
                return onDemand && status.isBefore(ChunkStatus.TERRAIN)?null:terrain.apply(x,z);
            }
            @Override protected void addSaveDependency(CompletableFuture<?> sync) {}
            @Override public int getTicketLevel() { return 0; }
            @Override public int getQueueLevel() { return 0; }
        });
    }

    @Override public ChunkAccess getChunk(int x,int z,ChunkStatus status,boolean loadOrGenerate) {
        if(onDemand) {
            int distance=center.getChessboardDistance(x,z);
            var dependencies=FEATURES.directDependencies();
            if(distance<dependencies.size() && status.isOrBefore(dependencies.get(distance))) return terrain.apply(x,z);
        }
        return super.getChunk(x,z,status,loadOrGenerate);
    }
}
