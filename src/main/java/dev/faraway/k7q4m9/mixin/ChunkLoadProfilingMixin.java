package dev.faraway.k7q4m9.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.server.level.ChunkGenerationTask;

@Mixin(ChunkMap.class)
abstract class ChunkLoadProfilingMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method="scheduleGenerationTask",at=@At("HEAD"))
    private void faraway$profileRequest(ChunkStatus target,ChunkPos pos,CallbackInfoReturnable<ChunkGenerationTask> callback) {
        if(WorldgenProfiler.enabled()) WorldgenProfiler.chunk(level,pos);
    }

    @WrapMethod(method="scheduleChunkLoad")
    private CompletableFuture<ChunkAccess> faraway$profileDiskLoad(ChunkPos pos,Operation<CompletableFuture<ChunkAccess>> original) {
        var work=WorldgenProfiler.chunk(level,pos);
        if(work==null) return original.call(pos);
        long start=System.nanoTime();
        WorldgenProfiler.startLoad(work,start);
        try {
            return original.call(pos).whenComplete((chunk,failure) -> WorldgenProfiler.loaded(work,chunk,start,failure));
        } catch(RuntimeException | Error failure) {
            WorldgenProfiler.loaded(work,null,start,failure);
            throw failure;
        }
    }
}
