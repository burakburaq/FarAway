package dev.faraway.k7q4m9.worldgen;

import dev.faraway.k7q4m9.mixin.NoiseGeneratorAccess;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

/** Evaluates raw surface columns against the active generator's noise and aquifers. */
final class NoiseColumns {
    record Column(int topY,int waterY,boolean wet,BlockState surface) {}
    private static final Predicate<BlockState> SOLID=Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
    private final WorldDefinition world;
    private final NoiseGeneratorAccess generator;
    private final int minY,maxY;
    private final Scan scan=new Scan();

    NoiseColumns(WorldDefinition world) {
        this.world=world;
        generator=(NoiseGeneratorAccess)(Object)world.noiseGenerator();
        var settings=world.noiseGenerator().generatorSettings().value().noiseSettings().clampToHeightAccessor(world.heights());
        minY=settings.minY();
        maxY=minY+settings.height()-1;
    }

    Column scan(int x,int z) {
        scan.reset(maxY);
        if(maxY>=minY) generator.faraway$scanColumn(world.heights(),world.randomState(),x,z,null,scan);
        return scan.column(world.heights().getMinY());
    }

    // Rectangular batches keep interpolation and aquifer caches local to a few nearby columns.
    Column[] batch(int x0,int z0,int width,int depth,int spacing,boolean[] needed,BooleanSupplier cancelled) {
        var result=new Column[needed.length];
        if(maxY<minY) {
            for(int i=0;i<needed.length;i++) if(needed[i]) result[i]=new Column(world.heights().getMinY(),0,false,null);
            return result;
        }
        var settings=world.noiseGenerator().generatorSettings().value();
        var area=new DensityVolume(width,maxY-minY+1,depth,x0,minY,z0,spacing,1,spacing);
        var scans=new Scan[needed.length];
        for(int i=0;i<needed.length;i++) if(needed[i]) {
            scans[i]=new Scan();
            scans[i].reset(maxY);
        }
        try(var chunk=new NoiseChunk(world.randomState(),null,settings,generator.faraway$globalFluidPicker().get(),Blender.empty(),area)) {
            var density=chunk.cachingSamplers().get(settings.noiseRouter().finalDensity());
            // Share aquifers across nearby samples without inflating the
            // density grid to include the unsampled gaps.
            for(int z=0;z<depth;z++) for(int x=0;x<width;x++) {
                var column=scans[z*width+x];
                if(column==null) continue;
                check(cancelled);
                int wx=area.blockX(x),wz=area.blockZ(z);
                var volume=new DensityVolume(1,area.sizeY(),1,wx,minY,wz);
                try(var values=density.sampleVolume(volume)) {
                    for(int y=maxY;y>=minY;y--) {
                        var block=chunk.aquifer().computeSubstance(wx,y,wz,values.get(y-minY));
                        if(column.test(block==null?settings.defaultBlock():block)) break;
                    }
                }
            }
        }
        for(int i=0;i<scans.length;i++) if(scans[i]!=null) result[i]=scans[i].column(world.heights().getMinY());
        return result;
    }

    static boolean isLava(BlockState state) { return state.is(Blocks.LAVA) || state.getFluidState().is(FluidTags.LAVA); }

    static final class Scan implements Predicate<BlockState> {
        private int y,topY,waterY;
        private boolean wet;
        private BlockState surface;

        void reset(int startY) { y=startY; waterY=0; wet=false; surface=null; }

        @Override public boolean test(BlockState state) {
            int currentY=y--;
            var fluid=state.getFluidState();
            if(!wet && (state.is(Blocks.WATER) || fluid.is(FluidTags.WATER))) { wet=true; waterY=currentY+1; }
            if(!SOLID.test(state) && !isLava(state)) return false;
            topY=currentY+1;
            surface=state;
            return true;
        }

        Column column(int emptyY) { return new Column(surface==null?emptyY:topY,waterY,wet,surface); }
    }

    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean()) throw new CancellationException();
    }
}
