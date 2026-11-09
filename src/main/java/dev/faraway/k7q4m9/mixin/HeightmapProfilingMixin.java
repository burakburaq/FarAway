package dev.faraway.k7q4m9.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Phase;
import java.util.Set;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Heightmap.class)
abstract class HeightmapProfilingMixin {
    @WrapMethod(method="primeHeightmaps")
    private static void faraway$profileHeightmaps(ChunkAccess chunk,Set<Heightmap.Types> types,Operation<Void> original) {
        var work=WorldgenProfiler.activeWork();
        if(work==null) work=WorldgenProfiler.chunk(chunk);
        try(var scope=WorldgenProfiler.scope(work,Phase.HEIGHTMAPS)) { original.call(chunk,types); }
    }
}
