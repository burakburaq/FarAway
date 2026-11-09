package dev.faraway.k7q4m9.terrain;

import dev.faraway.k7q4m9.worldgen.SurfaceGridSampler;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Phase;
import java.util.function.BooleanSupplier;

/** Two-triangle terrain with a world-space surface/canopy color texture. */
public record FarPlaneTile(TileKey key,int seaLevel,int side,byte[] rgba) {
    public static FarPlaneTile generate(TileKey key,SurfaceGridSampler sampler,BooleanSupplier cancelled) {return generate(key,sampler,true,cancelled);}
    public static FarPlaneTile generate(TileKey key,SurfaceGridSampler sampler,boolean trees,BooleanSupplier cancelled) {
        int side=key.blocks()/key.spacing();
        var samples=sampler.sampleRegion(key.originX(),key.originZ(),side,side,key.spacing(),cancelled);
        byte[] rgba=new byte[Math.multiplyExact(Math.multiplyExact(side,side),4)];
        for(int i=0;i<samples.length;i++) {
            if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            var sample=samples[i]; int color=color(sampler,key,i%side,i/side,sample,trees);
            rgba[i*4]=(byte)(color>>>16); rgba[i*4+1]=(byte)(color>>>8); rgba[i*4+2]=(byte)color; rgba[i*4+3]=(byte)(sample.terrain() || sample.water()?255:0);
        }
        return new FarPlaneTile(key,sampler.seaLevel(),side,rgba);
    }
    public static boolean usePlane(TileKey key,int seaLevel,dev.faraway.k7q4m9.worldgen.SurfaceSample[] samples) {
        return usePlane(key,seaLevel,samples,5);
    }
    public static boolean usePlane(TileKey key,int seaLevel,dev.faraway.k7q4m9.worldgen.SurfaceSample[] samples,int threshold) {
        return usePlaneForMaximum(key,seaLevel,maxSurfaceY(key,seaLevel,samples),threshold);
    }
    /** Maximum over the tile interior, excluding the neighbour halo. */
    public static int maxSurfaceY(TileKey key,int seaLevel,dev.faraway.k7q4m9.worldgen.SurfaceSample[] samples) {
        int side=key.blocks()/key.spacing(),stride=side+2,max=seaLevel;
        if(samples.length!=stride*stride) throw new IllegalArgumentException("Invalid surface grid size");
        for(int z=0;z<side;z++) for(int x=0;x<side;x++) {
            var sample=samples[(z+1)*stride+x+1];
            max=Math.max(max,sample.terrainTopY());
            if(sample.water()) max=Math.max(max,sample.waterTopY());
        }
        return max;
    }
    public static boolean usePlaneForMaximum(TileKey key,int seaLevel,int maxSurfaceY,int threshold) {
        return (long)key.spacing()*1000L>(long)threshold*((long)(maxSurfaceY-seaLevel)*1000L+111L);
    }
    private static int color(SurfaceGridSampler sampler,TileKey key,int x,int z,dev.faraway.k7q4m9.worldgen.SurfaceSample sample,boolean trees) {
        if(sample.water())return sample.waterColor();
        if(!trees)return sample.color();
        try(var profile=WorldgenProfiler.scope(Phase.TREES)) {
            return ForestCanopy.surfaceColor(sampler.seed(),key.originX()+x*key.spacing(),key.originZ()+z*key.spacing(),key.spacing(),sample,sampler.vegetationProfile(sample.biomeId()));
        }
    }
    public static FarPlaneTile fromSamples(TileKey key,SurfaceGridSampler sampler,dev.faraway.k7q4m9.worldgen.SurfaceSample[] samples,BooleanSupplier cancelled) {
        return fromSamples(key,sampler,samples,true,cancelled);
    }
    public static FarPlaneTile fromSamples(TileKey key,SurfaceGridSampler sampler,dev.faraway.k7q4m9.worldgen.SurfaceSample[] samples,boolean trees,BooleanSupplier cancelled) {
        return fromSamples(key,sampler.seaLevel(),samples,sampler,trees,cancelled);
    }
    public static FarPlaneTile fromSamples(TileKey key,int seaLevel,dev.faraway.k7q4m9.worldgen.SurfaceSample[] samples,BooleanSupplier cancelled) {
        return fromSamples(key,seaLevel,samples,null,false,cancelled);
    }
    private static FarPlaneTile fromSamples(TileKey key,int seaLevel,dev.faraway.k7q4m9.worldgen.SurfaceSample[] samples,SurfaceGridSampler sampler,boolean trees,BooleanSupplier cancelled) {
        int side=key.blocks()/key.spacing(),stride=side+2;
        if(samples.length!=stride*stride) throw new IllegalArgumentException("Invalid surface grid size");
        byte[] rgba=new byte[Math.multiplyExact(Math.multiplyExact(side,side),4)];
        for(int z=0;z<side;z++) for(int x=0;x<side;x++) {
            if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            var sample=samples[(z+1)*stride+x+1]; int color=sampler==null?(sample.water()?sample.waterColor():sample.color()):color(sampler,key,x,z,sample,trees),i=(z*side+x)*4;
            rgba[i]=(byte)(color>>>16); rgba[i+1]=(byte)(color>>>8); rgba[i+2]=(byte)color; rgba[i+3]=(byte)(sample.terrain() || sample.water()?255:0);
        }
        return new FarPlaneTile(key,seaLevel,side,rgba);
    }
}
