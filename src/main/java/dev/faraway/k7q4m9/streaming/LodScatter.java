package dev.faraway.k7q4m9.streaming;

import dev.faraway.k7q4m9.terrain.TileKey;

/** Stable, smooth world-space variation at a scale proportional to tile size. */
public final class LodScatter {
    private static final long FIXED_SEED=0x4F7A91D3B6C285E1L;
    public record Config(int strength,int scale,int seedOffset,int noiseBottom,int noiseTop,int mode,boolean enabled) {
        public Config(int strength,int scale,int seedOffset,int bottom,int top,int mode) { this(strength,scale,seedOffset,bottom,top,mode,true); }
        public Config(int strength,int scale,int seedOffset) { this(strength,scale,seedOffset,-50,50,0); }
        public Config(int strength,int scale,int seedOffset,int noiseBottom,int noiseTop) { this(strength,scale,seedOffset,noiseBottom,noiseTop,0); }
    }
    private LodScatter() {}
    public static double multiplier(long seed,TileKey tile) {
        return multiplier(seed,tile,15,4,-50,50);
    }
    public static double multiplier(Config config,TileKey tile) {
        if(!config.enabled() || config.strength()==0)return 1.0;
        return multiplier(FIXED_SEED+config.seedOffset(),tile,config.strength(),config.scale(),config.noiseBottom(),config.noiseTop());
    }
    public static int selectLod(Config config,TileKey tile,double allowed) {
        if(!config.enabled() || config.strength()==0)return level(allowed);
        if(config.mode()==0) return level(allowed*multiplier(config,tile));
        if(config.strength()==0 || allowed<=0) return level(allowed);
        int boundary=Math.max(1,Math.min(18,(int)Math.floor(Math.log(allowed)/Math.log(2)+0.5)));
        double ratio=allowed/Math.scalb(1.0,boundary);
        double noise=noise(FIXED_SEED+config.seedOffset(),tile,config.scale(),config.noiseBottom(),config.noiseTop());
        double threshold=Math.max(-1,Math.min(1,noise*2));
        return ratio>=1+config.strength()/100.0*threshold?boundary:boundary-1;
    }
    private static int level(double allowed) { return allowed<1?0:Math.min(18,Math.max(0,Math.getExponent(allowed))); }
    private static double multiplier(long seed,TileKey tile,int strength,int scale,int noiseBottom,int noiseTop) {
        return 1.0+strength/100.0*noise(seed,tile,scale,noiseBottom,noiseTop);
    }
    private static double noise(long seed,TileKey tile,int scale,int noiseBottom,int noiseTop) {
        double cell=(double)tile.blocks()*scale;
        double x=((double)tile.originX()+tile.blocks()*0.5)/cell;
        double z=((double)tile.originZ()+tile.blocks()*0.5)/cell;
        long ix=(long)Math.floor(x),iz=(long)Math.floor(z);
        double fx=smooth(x-ix),fz=smooth(z-iz);
        double a=lerp(value(seed,ix,iz),value(seed,ix+1,iz),fx);
        double b=lerp(value(seed,ix,iz+1),value(seed,ix+1,iz+1),fx);
        return (noiseBottom+(noiseTop-noiseBottom)*lerp(a,b,fz))/100.0;
    }
    private static double smooth(double x) { return x*x*(3-2*x); }
    private static double lerp(double a,double b,double t) { return a+(b-a)*t; }
    private static double value(long seed,long x,long z) {
        long h=seed^x*0x9E3779B97F4A7C15L^z*0xC2B2AE3D27D4EB4FL;
        h=(h^(h>>>30))*0xBF58476D1CE4E5B9L;
        h=(h^(h>>>27))*0x94D049BB133111EBL;
        return ((h^(h>>>31))>>>11) * 0x1.0p-53;
    }
}
