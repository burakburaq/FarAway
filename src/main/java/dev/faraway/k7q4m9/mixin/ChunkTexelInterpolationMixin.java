package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.client.ClientOptionLocks;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(LevelRenderer.class)
abstract class ChunkTexelInterpolationMixin {
    @ModifyArgs(method="lambda$addMainPass$0",at=@At(value="INVOKE",
        target="Lcom/mojang/renderpearl/api/device/GpuDevice;createSampler(Lcom/mojang/renderpearl/api/textures/AddressMode;Lcom/mojang/renderpearl/api/textures/AddressMode;Lcom/mojang/renderpearl/api/textures/FilterMode;Lcom/mojang/renderpearl/api/textures/FilterMode;ILjava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/textures/GpuSampler;"))
    private void faraway$useNearestTexels(Args args) {
        if(!ClientOptionLocks.enabled()) return;
        args.set(2,ClientOptionLocks.TEXEL_INTERPOLATION);
        args.set(3,ClientOptionLocks.TEXEL_INTERPOLATION);
    }
}
