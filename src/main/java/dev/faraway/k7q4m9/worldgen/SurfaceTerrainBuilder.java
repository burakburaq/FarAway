package dev.faraway.k7q4m9.worldgen;

import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;

/** Disposable placement substrate: shared native surface profiles, shallow ground and water surfaces. */
public final class SurfaceTerrainBuilder {
    public static final int GROUND_DEPTH=8;
    private SurfaceTerrainBuilder() {}
    public static int bottom(int top,int minY) { return Math.max(minY,top-GROUND_DEPTH); }
    public static ProtoChunk build(ProtoChunk target,WorldTerrainSampler sampler,BooleanSupplier cancelled) {
        var pos=new BlockPos.MutableBlockPos();int min=sampler.minY(),max=sampler.maxY();
        int ox=target.getPos().x()*16,oz=target.getPos().z()*16;
        for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
            if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            var column=sampler.placementColumn(ox+x,oz+z);
            var sample=column.sample();
            int terrainY=sample.terrainTopY();
            if(sample.terrain()) {
                int top=Math.min(max+1,terrainY);
                var surface=column.state();
                for(int y=bottom(top,min);y<top;y++) {
                    var block=y==top-1?surface:y>=top-4?Blocks.DIRT.defaultBlockState():Blocks.STONE.defaultBlockState();
                    target.setBlockState(pos.set(ox+x,y,oz+z),block,0);
                }
            }
            if(sample.water()) {
                int top=Math.min(max+1,sample.waterTopY());
                for(int y=Math.max(Math.max(min,terrainY),top-2);y<top;y++) target.setBlockState(pos.set(ox+x,y,oz+z),Blocks.WATER.defaultBlockState(),0);
            }
        }
        return target;
    }
}
