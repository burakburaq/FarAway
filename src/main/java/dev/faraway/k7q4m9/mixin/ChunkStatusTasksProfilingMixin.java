package dev.faraway.k7q4m9.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Phase;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkStatusTasks.class)
abstract class ChunkStatusTasksProfilingMixin {
    @WrapOperation(method="full",at=@At(value="INVOKE",target="Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private static CompletableFuture<ChunkAccess> faraway$profileFinalize(Supplier<ChunkAccess> worker,Executor executor,
        Operation<CompletableFuture<ChunkAccess>> original) {
        var work=WorldgenProfiler.activeWork();
        if(work==null) return original.call(worker,executor);
        Supplier<ChunkAccess> measured=() -> {
            try(var scope=WorldgenProfiler.scope(work,Phase.FINALIZE)) { return worker.get(); }
        };
        return original.call(measured,executor);
    }
}
