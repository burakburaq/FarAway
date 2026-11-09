package dev.faraway.k7q4m9.mixin;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.util.random.WeightedList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WeightedVariants.class)
public interface WeightedVariantsAccess {
    @Accessor("list")
    WeightedList<BlockStateModel> faraway$getVariants();
}
