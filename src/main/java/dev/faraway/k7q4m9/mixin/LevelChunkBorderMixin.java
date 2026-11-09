package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.render.ChunkBorderRevision;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkBorderMixin implements ChunkBorderRevision {
    @Unique private long faraway$revision;

    @Shadow public abstract Level getLevel();

    @Override public long faraway$borderRevision() { return faraway$revision; }

    @Inject(method="setBlockState",at=@At("RETURN"))
    private void faraway$blockChanged(BlockPos pos,BlockState state,int flags,CallbackInfoReturnable<BlockState> result) {
        if(result.getReturnValue()==null || !(getLevel() instanceof ClientLevel)) return;
        int x=pos.getX()&15,z=pos.getZ()&15;
        if(x==0 || x==15 || z==0 || z==15) faraway$revision++;
    }

    @Inject(method="replaceWithPacketData",at=@At("RETURN"))
    private void faraway$chunkReplaced(int x,int z,ClientboundLevelChunkPacketData data,CallbackInfo ci) {
        faraway$revision++;
    }
}
