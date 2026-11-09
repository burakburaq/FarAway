package dev.faraway.k7q4m9.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Phase;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(NoiseBasedChunkGenerator.class)
abstract class NoiseWorldgenProfilingMixin {
    @WrapOperation(method="buildTerrain",at=@At(value="INVOKE",target="Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChunkAccess> faraway$profileTerrainWorker(Supplier<ChunkAccess> worker,Executor executor,
        Operation<CompletableFuture<ChunkAccess>> original) {
        var work=WorldgenProfiler.activeWork();
        if(work==null) return original.call(worker,executor);
        Supplier<ChunkAccess> measured=() -> {
            try(var scope=WorldgenProfiler.scope(work,Phase.OTHER)) { return worker.get(); }
        };
        return original.call(measured,executor);
    }

    @WrapMethod(method="createNoiseChunk")
    private NoiseChunk faraway$profileNoiseSetup(ChunkAccess chunk,StructureManager structures,Blender blender,RandomState random,
        NoiseSettings settings,Operation<NoiseChunk> original) {
        try(var scope=WorldgenProfiler.scope(Phase.NOISE)) { return original.call(chunk,structures,blender,random,settings); }
    }

    @WrapMethod(method="doFill")
    private void faraway$profileNoise(NoiseChunk noise,ChunkAccess chunk,Operation<Void> original) {
        try(var scope=WorldgenProfiler.scope(Phase.NOISE)) { original.call(noise,chunk); }
    }

    @WrapMethod(method="buildSurface")
    private void faraway$profileSurface(ChunkAccess chunk,NoiseChunk noise,RandomState random,BiomeManager biomes,
        Set<Holder<Biome>> possibleBiomes,MaterialRule rule,Operation<Void> original) {
        try(var scope=WorldgenProfiler.scope(Phase.SURFACE)) { original.call(chunk,noise,random,biomes,possibleBiomes,rule); }
    }

    @WrapMethod(method="generateCarvers")
    private void faraway$profileCarvers(ChunkAccess chunk,Blender blender,NoiseChunk noise,RandomState random,
        BiomeManager biomes,WorldGenRegion region,MaterialRule rule,Operation<Void> original) {
        try(var scope=WorldgenProfiler.scope(Phase.CARVERS)) { original.call(chunk,blender,noise,random,biomes,region,rule); }
    }
}
