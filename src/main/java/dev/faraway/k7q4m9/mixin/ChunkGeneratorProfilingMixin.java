package dev.faraway.k7q4m9.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Phase;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorProfilingMixin {
    @WrapOperation(method="createBiomes",at=@At(value="INVOKE",target="Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChunkAccess> faraway$profileBiomeWorker(Supplier<ChunkAccess> worker,Executor executor,
        Operation<CompletableFuture<ChunkAccess>> original) {
        var work=WorldgenProfiler.activeWork();
        if(work==null) return original.call(worker,executor);
        Supplier<ChunkAccess> measured=() -> {
            try(var scope=WorldgenProfiler.scope(work,Phase.BIOMES)) { return worker.get(); }
        };
        return original.call(measured,executor);
    }

    @WrapOperation(method="applyBiomeDecoration",at=@At(value="INVOKE",
        target="Lnet/minecraft/world/level/levelgen/placement/FeaturePlacer;placeWithBiomeCheck(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean faraway$profileFeatureStep(FeaturePlacer placer,PlacedFeature feature,RandomSource random,BlockPos pos,
        Operation<Boolean> original,@Local(name="stepIndex") int stepIndex) {
        Phase phase=stepIndex==GenerationStep.Decoration.VEGETAL_DECORATION.ordinal()?Phase.VEGETATION:Phase.OTHER_FEATURES;
        try(var scope=WorldgenProfiler.scope(phase)) { return original.call(placer,feature,random,pos); }
    }
}
