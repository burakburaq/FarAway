package dev.faraway.k7q4m9.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.faraway.k7q4m9.client.ClientOptionLocks;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets="net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder",remap=false)
abstract class SodiumChunkBuilderMixin {
    @ModifyExpressionValue(method="getThreadCount",at=@At(value="FIELD",opcode=Opcodes.GETFIELD,
        target="Lnet/caffeinemc/mods/sodium/client/gui/SodiumOptions$PerformanceSettings;chunkBuilderThreads:I"))
    private static int faraway$useDefaultThreads(int configured) {
        return ClientOptionLocks.enabled()?ClientOptionLocks.SODIUM_THREADS:configured;
    }
}
