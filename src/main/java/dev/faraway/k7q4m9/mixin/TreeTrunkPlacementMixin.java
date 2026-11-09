package dev.faraway.k7q4m9.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.faraway.k7q4m9.worldgen.DetachedDecorationSampler.TreeTrunkSink;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TrunkPlacer.class)
abstract class TreeTrunkPlacementMixin {
    @WrapOperation(
        method="placeLog(Lnet/minecraft/world/level/WorldGenLevel;Ljava/util/function/BiConsumer;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/feature/TreeFeature;Ljava/util/function/Function;)Z",
        at=@At(value="INVOKE",target="Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"))
    private void faraway$recordTrunk(BiConsumer<?,?> setter,Object pos,Object state,Operation<Void> original,
                                    @Local(argsOnly=true) WorldGenLevel level) {
        original.call(setter,pos,state);
        if(level instanceof TreeTrunkSink sink) sink.recordTreeTrunk((BlockPos)pos,(BlockState)state);
    }
}
