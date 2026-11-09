package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.client.FarAwayClient;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class BenchmarkInputMixin extends ClientInput {
    @Inject(method="tick",at=@At("TAIL"))
    private void farawayLockMovement(CallbackInfo ci) {
        if(FarAwayClient.benchmarkLocked() || dev.faraway.k7q4m9.client.Freecam.active()) { keyPresses=Input.EMPTY; moveVector=Vec2.ZERO; }
    }
}
