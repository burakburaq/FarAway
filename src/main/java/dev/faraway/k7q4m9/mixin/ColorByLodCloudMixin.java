package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.client.LodSettings;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
public abstract class ColorByLodCloudMixin {
    @Inject(method="render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",at=@At("HEAD"),cancellable=true)
    private void farawayHideClouds(CallbackInfo ci) { if(LodSettings.get().colorByLod()) ci.cancel(); }
    @Inject(method="renderOit",at=@At("HEAD"),cancellable=true)
    private void farawayHideOitClouds(CallbackInfo ci) { if(LodSettings.get().colorByLod()) ci.cancel(); }
}
