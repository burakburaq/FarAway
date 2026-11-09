package dev.faraway.k7q4m9.terrain;

/** Pure world-space vegetation field. No worldgen, block states or tile-dependent seeds. */
public final class VegetationField {
    public record Profile(double density,int height,int radius,int color,boolean conifer,int logMaterial) {
        public Profile(double density,int height,int radius,int color,boolean conifer) { this(density,height,radius,color,conifer,6); }
    }
    public record Surface(int ground,boolean wet,Profile profile,int foliageColor) {
        public Surface(int ground,boolean wet,Profile profile) { this(ground,wet,profile,profile.color()); }
    }
    @FunctionalInterface public interface Source { Surface sample(int x,int z); }
    public static long hash(long seed,int x,int z) {
        long v=seed ^ x*0x9e3779b97f4a7c15L ^ z*0xc2b2ae3d27d4eb4fL;
        v=(v^(v>>>30))*0xbf58476d1ce4e5b9L; v=(v^(v>>>27))*0x94d049bb133111ebL; return v^(v>>>31);
    }
    public static double unit(long value) { return (value>>>11)*0x1.0p-53; }
    public static double noise(long seed,double x,double z,int period) {
        int ix=(int)Math.floor(x/period),iz=(int)Math.floor(z/period);
        double tx=x/period-ix,tz=z/period-iz; tx=tx*tx*(3-2*tx); tz=tz*tz*(3-2*tz);
        double a=unit(hash(seed,ix,iz)),b=unit(hash(seed,ix+1,iz));
        double c=unit(hash(seed,ix,iz+1)),d=unit(hash(seed,ix+1,iz+1));
        return (a+(b-a)*tx)*(1-tz)+(c+(d-c)*tx)*tz;
    }
    public static double density(long seed,double x,double z,Profile p) {
        return Math.min(1,p.density()*(.25+1.25*noise(seed,x,z,128)));
    }
    public static int height(long seed,int x,int z,Profile p) {
        return Math.max(4,p.height()+(int)Math.round(6*(noise(seed^91,x,z,64)-.5)));
    }
    public static int color(long seed,int x,int z,Profile p) {
        double shade=.86+.26*noise(seed^127,x,z,32);
        return (Math.min(255,(int)((p.color()>>16&255)*shade))<<16)|(Math.min(255,(int)((p.color()>>8&255)*shade))<<8)|Math.min(255,(int)((p.color()&255)*shade));
    }
}
