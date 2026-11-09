package dev.faraway.k7q4m9.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Phase;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(StructureStart.class)
abstract class StructurePlacementProfilingMixin {
    @WrapMethod(method="placeInChunk")
    private void faraway$profileStructurePlacement(WorldGenLevel level,StructureManager structures,ChunkGenerator generator,
        RandomSource random,BoundingBox bounds,ChunkPos pos,Operation<Void> original) {
        try(var scope=WorldgenProfiler.scope(Phase.STRUCTURE_PLACEMENT)) {
            original.call(level,structures,generator,random,bounds,pos);
        }
    }
}
