package dev.faraway.k7q4m9.terrain;

import dev.faraway.k7q4m9.worldgen.SurfaceGridSampler;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;

/** Sampled 3D noise terrain, including internal air and fluid layers. */
public final class VoxelTile {
    private final TileKey key;
    private final int bottom,stepY,layers,cells,stride;
    private final int[] colors;
    private final byte[] kinds;
    private final short[] waterSurface;
    private final short[] terrainSurface;
    private final int[] waterColors;
    private final boolean[] wet;
    private final int[] surfaceColors;
    private final byte[] surfaceMaterials;
    private boolean surfaceOnly;
    private boolean blendColors=true;
    VoxelTile(TileKey key,int bottom,int stepY,int layers) {
        this.key=key; this.bottom=bottom; this.stepY=stepY; this.layers=layers;
        this.cells=key.blocks()/key.spacing(); this.stride=cells+2;
        waterSurface=new short[stride*stride]; terrainSurface=new short[stride*stride]; waterColors=new int[stride*stride]; wet=new boolean[stride*stride]; surfaceColors=new int[stride*stride]; surfaceMaterials=new byte[stride*stride];
        colors=new int[stride*stride*layers]; kinds=new byte[colors.length];
    }
    private VoxelTile(TileKey key,int bottom,int stepY,int layers,boolean surfaceOnly) {
        this.key=key; this.bottom=bottom; this.stepY=stepY; this.layers=layers;
        this.cells=key.blocks()/key.spacing(); this.stride=cells+2;
        waterSurface=new short[stride*stride]; terrainSurface=new short[stride*stride]; waterColors=new int[stride*stride]; wet=new boolean[stride*stride]; surfaceColors=new int[stride*stride]; surfaceMaterials=new byte[stride*stride];
        this.surfaceOnly=surfaceOnly; colors=new int[0]; kinds=new byte[0];
    }
    public static VoxelTile generate(TileKey key,SurfaceGridSampler sampler,BooleanSupplier cancelled) {
        return generate(key,sampler,cancelled,dev.faraway.k7q4m9.client.LodSettings.blendEnabled(key.spacing()));
    }
    static VoxelTile generate(TileKey key,SurfaceGridSampler sampler,BooleanSupplier cancelled,boolean blendColors) {
        return fromSamples(key,sampler,cancelled,blendColors,sampleSurface(key,sampler,cancelled));
    }
    public static dev.faraway.k7q4m9.worldgen.SurfaceSample[] sampleSurface(TileKey key,SurfaceGridSampler sampler,BooleanSupplier cancelled) {
        int cells=key.blocks()/key.spacing(),stride=cells+2;
        var ocean=sampler.sampleRegion(Math.subtractExact(key.originX(),key.spacing()),Math.subtractExact(key.originZ(),key.spacing()),stride,stride,key.spacing(),cancelled);
        if(ocean.length!=stride*stride) throw new IllegalStateException("Surface sampler returned an invalid grid size");
        return ocean;
    }
    public static VoxelTile fromSamples(TileKey key,SurfaceGridSampler sampler,BooleanSupplier cancelled,boolean blendColors,dev.faraway.k7q4m9.worldgen.SurfaceSample[] ocean) {
        int cells=key.blocks()/key.spacing(),stride=cells+2;
        if(ocean.length!=stride*stride) throw new IllegalArgumentException("Invalid surface grid size");
        int step=Math.min(key.spacing(),16), bottom=sampler.minY();
        int layers=(sampler.maxY()-bottom+1+step-1)/step;
        boolean allOcean=true;
        for(int z=-1;z<=cells;z++) for(int x=-1;x<=cells;x++) {
            if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            var sample=ocean[(z+1)*stride+x+1];
            if(!sample.water() || sample.waterTopY()!=sampler.seaLevel() || sample.terrainTopY()>=sampler.seaLevel()) allOcean=false;
        }
        var tile=new VoxelTile(key,bottom,step,allOcean?0:layers,true);
        tile.blendColors=blendColors;
        for(int i=0;i<ocean.length;i++) {
            var sample=ocean[i];
            tile.surface(i%stride-1,i/stride-1,sample.terrainTopY(),sample.waterTopY(),sample.waterColor(),sample.water());
            tile.surfaceColors[i]=sample.color();
            tile.surfaceMaterials[i]=(byte)sample.material();
        }
        return tile;
    }
    void surface(int x,int z,int terrainY,int waterY,int color,boolean hasWater) {
        int i=(z+1)*stride+x+1;
        terrainSurface[i]=(short)terrainY; waterSurface[i]=(short)waterY; waterColors[i]=color; wet[i]=hasWater;
    }
    void cell(int x,int y,int z,int kind,int color) { int i=index(x,y,z); kinds[i]=(byte)kind; colors[i]=color; }
    private int index(int x,int y,int z) { return (y*stride+z+1)*stride+x+1; }
    public int kind(int x,int y,int z) { return y<0 || y>=layers?0:surfaceOnly?(wet(x,z)?0:bottom+y*stepY<terrainSurface(x,z)?1:0):kinds[index(x,y,z)]; }
    public int color(int x,int y,int z) { return surfaceOnly?surfaceColors[(z+1)*stride+x+1]:colors[index(x,y,z)]; }
    public int material(int x,int z) { return Byte.toUnsignedInt(surfaceMaterials[(z+1)*stride+x+1]); }
    public boolean blendColors() { return blendColors; }
    public int surfaceColor(int x,int z) { return surfaceColors[(z+1)*stride+x+1]; }
    public int waterSurface(int x,int z) { return waterSurface[(z+1)*stride+x+1]; }
    public int terrainSurface(int x,int z) { return terrainSurface[(z+1)*stride+x+1]; }
    public boolean wet(int x,int z) { return wet[(z+1)*stride+x+1]; }
    public int waterColor(int x,int z) { return waterColors[(z+1)*stride+x+1]; }
    public boolean oceanOnly() { return layers==0; }
    public int cells() { return cells; }
    public TileKey key() { return key; }
    public int bottom() { return bottom; }
    public int layers() { return layers; }
    public int stepY() { return stepY; }
}
