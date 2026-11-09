package dev.faraway.k7q4m9.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Phase;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ChunkStep.class)
abstract class ChunkStepProfilingMixin {
    @WrapMethod(method="apply")
    private CompletableFuture<ChunkAccess> faraway$profileStep(WorldGenContext context,
        StaticCache2D<GenerationChunkHolder> cache,ChunkAccess chunk,Operation<CompletableFuture<ChunkAccess>> original) {
        if(!WorldgenProfiler.enabled()) return original.call(context,cache,chunk);
        var work=WorldgenProfiler.chunk(context.level(),chunk.getPos());
        if(work==null) return original.call(context,cache,chunk);
        var status=((ChunkStep)(Object)this).targetStatus();
        long start=System.nanoTime(),active=work.active();
        WorldgenProfiler.startStage(chunk,work,start);
        Phase phase=Phase.OTHER;
        if(chunk.getPersistedStatus().isBefore(status)) {
            if(status==ChunkStatus.STRUCTURE_STARTS) phase=Phase.STRUCTURE_STARTS;
            else if(status==ChunkStatus.STRUCTURE_REFERENCES) phase=Phase.STRUCTURE_REFERENCES;
            else if(status==ChunkStatus.BIOMES) phase=Phase.BIOMES;
            else if(status==ChunkStatus.FEATURES) phase=Phase.OTHER_FEATURES;
            else if(status==ChunkStatus.SPAWN) phase=Phase.SPAWN;
        }
        if(status==ChunkStatus.INITIALIZE_LIGHT) phase=Phase.LIGHT_PREP;
        CompletableFuture<ChunkAccess> result;
        try(var scope=WorldgenProfiler.scope(work,phase)) {
            result=original.call(context,cache,chunk);
        } catch(RuntimeException | Error failure) {
            WorldgenProfiler.endStage(chunk,work,status,start,active,failure);
            throw failure;
        }
        return result.whenComplete((value,failure) -> WorldgenProfiler.endStage(chunk,work,status,start,active,failure));
    }
}
