package dev.faraway.k7q4m9.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Phase;
import java.util.function.Supplier;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ThreadedLevelLightEngine.class)
abstract class LightBatchProfilingMixin {
    @WrapOperation(method={"initializeLight","lightChunk"},at=@At(value="INVOKE",
        target="Lnet/minecraft/util/Util;name(Ljava/lang/Runnable;Ljava/util/function/Supplier;)Ljava/lang/Runnable;"))
    private Runnable faraway$profileChunkLight(Runnable action,Supplier<String> name,Operation<Runnable> original,
        ChunkAccess chunk,boolean lighted) {
        var work=WorldgenProfiler.activeWork();
        if(work==null) return original.call(action,name);
        Runnable measured=() -> {
            try(var scope=WorldgenProfiler.scope(work,Phase.LIGHT_PROPAGATE)) { action.run(); }
        };
        return original.call(measured,name);
    }

    @WrapMethod(method="runUpdate")
    private void faraway$profileLightBatch(Operation<Void> original) {
        if(!WorldgenProfiler.enabled()) { original.call(); return; }
        long start=System.nanoTime();
        try { original.call(); }
        finally { WorldgenProfiler.lightBatch(System.nanoTime()-start); }
    }
}
