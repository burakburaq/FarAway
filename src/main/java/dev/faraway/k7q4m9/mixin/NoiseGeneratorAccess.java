package dev.faraway.k7q4m9.mixin;

import java.util.OptionalInt;
import java.util.function.Predicate;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.world.level.levelgen.Aquifer;
import java.util.function.Supplier;

/** 26.3's exact chunkless column path, with early exit and no full BlockState array. */
@Mixin(NoiseBasedChunkGenerator.class)
public interface NoiseGeneratorAccess {
    @Accessor("globalFluidPicker")
    Supplier<Aquifer.FluidPicker> faraway$globalFluidPicker();
    @Invoker("doFill")
    void faraway$fillNoise(net.minecraft.world.level.levelgen.NoiseChunk noise,net.minecraft.world.level.chunk.ChunkAccess chunk);

    @Invoker("iterateNoiseColumn")
    OptionalInt faraway$scanColumn(LevelHeightAccessor heights,RandomState random,int x,int z,
                                  MutableObject<NoiseColumn> output,Predicate<BlockState> stop);
}
