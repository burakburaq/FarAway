package dev.faraway.k7q4m9.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Phase;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;
import net.minecraft.world.level.levelgen.feature.FallenTreeFeature;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(FeaturePlacer.class)
abstract class FeaturePlacementProfilingMixin {
    @WrapMethod(method="place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z")
    private boolean faraway$profileFeature(PlacedFeature placed,RandomSource random,BlockPos pos,boolean biomeCheck,Operation<Boolean> original) {
        if(!WorldgenProfiler.measuring()) return original.call(placed,random,pos,biomeCheck);
        var feature=placed.feature().value();
        Phase phase=switch(feature) {
            case TreeFeature ignored -> Phase.TREES;
            case FallenTreeFeature ignored -> Phase.TREES;
            case AbstractOreFeature ignored -> Phase.ORES;
            case LakeFeature ignored -> Phase.LAKES;
            default -> null;
        };
        if(phase==null) return original.call(placed,random,pos,biomeCheck);
        try(var scope=WorldgenProfiler.scope(phase)) { return original.call(placed,random,pos,biomeCheck); }
    }
}
