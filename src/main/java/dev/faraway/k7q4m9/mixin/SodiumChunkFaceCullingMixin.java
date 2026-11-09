package dev.faraway.k7q4m9.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.faraway.k7q4m9.client.ClientOptionLocks;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets="net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer",remap=false)
abstract class SodiumChunkFaceCullingMixin {
    @ModifyExpressionValue(method="prepare",at=@At(value="FIELD",opcode=Opcodes.GETFIELD,
        target="Lnet/caffeinemc/mods/sodium/client/gui/SodiumOptions$PerformanceSettings;useBlockFaceCulling:Z"))
    private boolean faraway$useFaceCulling(boolean configured) {
        return ClientOptionLocks.enabled()?ClientOptionLocks.BLOCK_FACE_CULLING:configured;
    }
}
