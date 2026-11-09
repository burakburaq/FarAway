package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.client.LodSettings;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.sugar.Local;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    // Run after vanilla's final distance assignment, before RETURN callbacks such
    // as Sodium's copy into its independent FogParameters record.
    @Inject(method="setupFog",at=@At(value="FIELD",target="Lnet/minecraft/client/renderer/fog/FogData;renderDistanceEnd:F",opcode=181,shift=At.Shift.AFTER),require=1,allow=1)
    private void farawayPrepareDistanceFog(Camera camera,int renderDistance,DeltaTracker delta,float darken,ClientLevel level,CallbackInfoReturnable<FogData> cir,@Local FogData fog) {
        farawayApplyDistanceFog(fog,camera);
    }
    private static void farawayApplyDistanceFog(FogData fog,Camera camera) {
        if(LodSettings.get().disableDistanceFog()) {
            fog.renderDistanceStart=10_000_000.0f;
            fog.renderDistanceEnd=20_000_000.0f;
            // Atmospheric fog is combined with distance fog in both vanilla and Sodium.
            // Leave water, lava and powdered-snow fog under their own environment rules.
            var type=camera.getFluidInCamera();
            if(type==net.minecraft.world.level.material.FogType.NONE || type==net.minecraft.world.level.material.FogType.ATMOSPHERIC) {
                fog.environmentalStart=10_000_000.0f;
                fog.environmentalEnd=20_000_000.0f;
            }
        }
    }
}
