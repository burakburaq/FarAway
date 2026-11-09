package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.client.FarAwayClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class BenchmarkMouseMixin {
    @org.spongepowered.asm.mixin.Shadow private double accumulatedDX;
    @org.spongepowered.asm.mixin.Shadow private double accumulatedDY;
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private net.minecraft.client.Minecraft minecraft;
    @Inject(method="turnPlayer",at=@At("HEAD"),cancellable=true)
    private void farawayLockCamera(double delta,CallbackInfo ci) {
        if(FarAwayClient.benchmarkLocked()) { ci.cancel(); return; }
        if(dev.faraway.k7q4m9.client.Freecam.active()) {
            double s=minecraft.options.sensitivity().get()*0.6+0.2, factor=s*s*s*8;
            dev.faraway.k7q4m9.client.Freecam.turn(accumulatedDX*factor*(minecraft.options.invertMouseX().get()?-1:1),accumulatedDY*factor*(minecraft.options.invertMouseY().get()?-1:1));
            ci.cancel();
        }
    }
    @Inject(method="onScroll",at=@At("HEAD"),cancellable=true)
    private void farawayScroll(long handle,double x,double y,CallbackInfo ci) {
        if(dev.faraway.k7q4m9.client.Freecam.active() && minecraft.gui.screen()==null && handle==minecraft.getWindow().handle()) {
            dev.faraway.k7q4m9.client.Freecam.scroll(y); ci.cancel();
        }
    }
}
