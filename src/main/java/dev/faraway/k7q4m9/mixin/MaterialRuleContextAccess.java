package dev.faraway.k7q4m9.mixin;

import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MaterialRuleContext.class)
public interface MaterialRuleContextAccess {
    @Invoker("<init>")
    static MaterialRuleContext faraway$create(MaterialSystem system,RandomState random,DensityVolume volume,
        DensitySamplerSet samplers,Function<BlockPos,Holder<Biome>> biomes,WorldGenerationContext generation,
        Set<Holder<Biome>> possibleBiomes) {
        throw new AssertionError();
    }

    @Invoker("updateXZ")
    void faraway$updateXZ(int x,int z,int gradientX,int gradientZ);

    @Invoker("updateY")
    void faraway$updateY(int stoneAbove,int stoneBelow,int waterY,int y);
}
