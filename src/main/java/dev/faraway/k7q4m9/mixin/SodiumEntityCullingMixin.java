package dev.faraway.k7q4m9.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.faraway.k7q4m9.client.ClientOptionLocks;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets="net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer",remap=false)
abstract class SodiumEntityCullingMixin {
    @ModifyExpressionValue(method="setupTerrain",at=@At(value="FIELD",opcode=Opcodes.GETFIELD,
        target="Lnet/caffeinemc/mods/sodium/client/gui/SodiumOptions$PerformanceSettings;useEntityCulling:Z"))
    private boolean faraway$useEntityCulling(boolean configured) {
        return ClientOptionLocks.enabled()?ClientOptionLocks.ENTITY_CULLING:configured;
    }
}
