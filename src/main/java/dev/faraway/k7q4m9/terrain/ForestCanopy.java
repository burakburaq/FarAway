package dev.faraway.k7q4m9.terrain;

import dev.faraway.k7q4m9.worldgen.SurfaceSample;
import dev.faraway.k7q4m9.worldgen.VegetationProfiles;

/** Top-down canopy color, using the same world-space 8-block anchors and density as voxel trees. */
public final class ForestCanopy {
    private ForestCanopy() {}
    public static int surfaceColor(long seed,int x,int z,int spacing,SurfaceSample sample,VegetationField.Profile profile) {
        if(sample.water())return sample.waterColor();
        int ground=sample.color();
        if(!sample.terrain() || profile.density()<=0 || profile.radius()<=0)return ground;
        double coverage=0;
        if(spacing<=8) {
            // Bound work by the texel footprint, never by tile area or world height.
            int n=spacing<=2?1:2;
            for(int dz=0;dz<n;dz++)for(int dx=0;dx<n;dx++)
                coverage+=covered(seed,x+(dx+.5)*spacing/n,z+(dz+.5)*spacing/n,profile)?1.0/(n*n):0;
        } else {
            // Large LODs use expected crown cover: no individual tree candidates.
            double radius=profile.radius()+.5,area=Math.PI*radius*radius/64;
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++) {
                double density=VegetationField.density(seed,x+(dx+.5)*spacing/2,z+(dz+.5)*spacing/2,profile);
                coverage+=(1-Math.exp(-density*area))*.25;
            }
        }
        if(coverage<=0)return ground;
        int leaves=VegetationProfiles.foliageTint(profile,sample.foliageColor());
        if(profile.logMaterial()==18 || profile.logMaterial()==19)leaves=profile.color();
        double shade=.68+.20*VegetationField.noise(seed^127,x+spacing*.5,z+spacing*.5,32);
        int r=(int)Math.round((ground>>>16&255)*(1-coverage)+(leaves>>>16&255)*shade*coverage);
        int g=(int)Math.round((ground>>>8&255)*(1-coverage)+(leaves>>>8&255)*shade*coverage);
        int b=(int)Math.round((ground&255)*(1-coverage)+(leaves&255)*shade*coverage);
        return r<<16|g<<8|b;
    }
    private static boolean covered(long seed,double x,double z,VegetationField.Profile p) {
        int radius=Math.min(12,p.radius()+1);
        int minX=(int)Math.floor((x-radius-8)/8),maxX=(int)Math.floor((x+radius)/8);
        int minZ=(int)Math.floor((z-radius-8)/8),maxZ=(int)Math.floor((z+radius)/8);
        for(int az=minZ;az<=maxZ;az++)for(int ax=minX;ax<=maxX;ax++) {
            long h=VegetationField.hash(seed,ax,az);
            int tx=ax*8+1+(int)((h>>>8)&7),tz=az*8+1+(int)((h>>>16)&7);
            int r=Math.min(12,p.radius()+((int)(h>>>24)&1));
            double dx=x-tx,dz=z-tz;
            if(dx*dx+dz*dz<=r*r && VegetationField.unit(h)<VegetationField.density(seed,tx,tz,p))return true;
        }
        return false;
    }
}
