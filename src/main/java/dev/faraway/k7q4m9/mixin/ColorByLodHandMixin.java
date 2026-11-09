package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.client.LodSettings;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class ColorByLodHandMixin {
    @Inject(method="renderItemInHand",at=@At("HEAD"),cancellable=true)
    private void farawayHideHand(CallbackInfo ci) { if(LodSettings.get().colorByLod() || dev.faraway.k7q4m9.client.Freecam.active()) ci.cancel(); }
}
