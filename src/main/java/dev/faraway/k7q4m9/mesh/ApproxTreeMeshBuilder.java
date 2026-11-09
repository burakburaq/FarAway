package dev.faraway.k7q4m9.mesh;

import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import dev.faraway.k7q4m9.terrain.*;
import dev.faraway.k7q4m9.worldgen.*;

/** Sparse LOD vegetation: shared world-space trees at LOD 1-3, canopy field at LOD 4+. */
public final class ApproxTreeMeshBuilder {
    private static final int[][] D={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
    private static final int[][][] F={{{0,0,1},{0,1,1},{0,0,0},{0,1,0}},{{1,0,0},{1,1,0},{1,0,1},{1,1,1}},
        {{0,0,0},{1,0,0},{0,0,1},{1,0,1}},{{0,1,1},{1,1,1},{0,1,0},{1,1,0}},
        {{0,0,0},{0,1,0},{1,0,0},{1,1,0}},{{1,0,1},{1,1,1},{0,0,1},{0,1,1}}};
    private static final int[] TRI={0,1,2,2,1,3};
    public record Stats(int candidates,int surfaceSamples,int voxels,int triangles,long generationNs,long meshNs) {}
    private Stats stats;
    public Stats stats() { return stats; }
    public TileMesh build(TileKey key,WorldDefinition world,SurfaceGridSampler sampler,int cameraX,int cameraZ,int radius,BooleanSupplier cancelled) {
        return build(key,world.seed(),(x,z) -> {
            var s=sampler.vegetationSurface(x,z,key.spacing());
            var p=sampler.vegetationProfile(s.biomeId());
            int foliage=VegetationProfiles.foliageTint(p,s.foliageColor());
            return new VegetationField.Surface(s.terrainTopY(),s.water() || !s.terrain(),p,foliage);
        },cameraX,cameraZ,radius,cancelled);
    }
    public TileMesh build(TileKey key,long seed,VegetationField.Source source,int cameraX,int cameraZ,int radius,BooleanSupplier cancelled) {
        long start=System.nanoTime(); int s=key.spacing(),sy=Math.min(s,4),samples=0,candidates=0,side=key.blocks()/key.spacing();
        var cells=new HashMap<Long,Integer>();
        if(s<=8) {
            // Fixed 8-block anchor grid for every near LOD, with a 24-block crown halo.
            int minX=Math.floorDiv(key.originX()-24,8),maxX=Math.floorDiv(key.originX()+key.blocks()+24,8);
            int minZ=Math.floorDiv(key.originZ()-24,8),maxZ=Math.floorDiv(key.originZ()+key.blocks()+24,8);
            for(int az=minZ;az<=maxZ;az++) for(int ax=minX;ax<=maxX;ax++) {
                check(cancelled); long h=VegetationField.hash(seed,ax,az);
                int x=ax*8+1+(int)((h>>>8)&7),z=az*8+1+(int)((h>>>16)&7);
                if(!inside(x,z,24,cameraX,cameraZ,radius)) continue;
                var surface=source.sample(x,z); samples++;
                var p=surface.profile(); if(surface.wet() || VegetationField.unit(h)>=VegetationField.density(seed,x,z,p)) continue;
                candidates++; int height=VegetationField.height(seed,x,z,p),ground=surface.ground();
                int r=Math.min(12,p.radius()+((int)(h>>>24)&1));
                int crownBottom=ground+Math.max(2,height/3),top=ground+height;
                int color=((TextureMaterial.leafForLog(p.logMaterial())+1)<<24)|surface.foliageColor();
                for(int vz=Math.floorDiv(z-r-key.originZ(),s);vz<=Math.floorDiv(z+r-key.originZ(),s);vz++)
                    for(int vx=Math.floorDiv(x-r-key.originX(),s);vx<=Math.floorDiv(x+r-key.originX(),s);vx++) {
                        if(vx< -1 || vx>side || vz< -1 || vz>side) continue;
                        double dx=Math.max(0,Math.abs(key.originX()+(vx+.5)*s-x)-s*.5);
                        double dz=Math.max(0,Math.abs(key.originZ()+(vz+.5)*s-z)-s*.5);
                        for(int vy=Math.floorDiv(crownBottom,sy);vy<=Math.floorDiv(top-1,sy);vy++) {
                            double t=Math.max(0,Math.min(1,((vy+.5)*sy-crownBottom)/(top-crownBottom)));
                            double shape=p.conifer()?1-t*.85:Math.sqrt(Math.max(.15,1-Math.pow(2*t-1,2)));
                            if(dx*dx+dz*dz<=r*r*shape*shape) put(cells,vx,vy,vz,color,side);
                        }
                    }
                if(s<=4) for(int vy=Math.floorDiv(ground,sy);vy<Math.floorDiv(crownBottom,sy);vy++)
                    put(cells,Math.floorDiv(x-key.originX(),s),vy,Math.floorDiv(z-key.originZ(),s),0x80000000|((p.logMaterial()+1)<<24)|0xffffff,side);
            }
        } else {
            // No tree candidates: directly sample the same low-frequency forest field.
            for(int z=-1;z<=side;z++) for(int x=-1;x<=side;x++) {
                check(cancelled); int wx=key.originX()+x*s,wz=key.originZ()+z*s;
                if(!inside(wx,wz,s,cameraX,cameraZ,radius)) continue;
                var surface=source.sample(wx,wz); samples++;
                var p=surface.profile(); if(surface.wet() || p.density()==0) continue;
                double coverage=0;
                for(int dz=0;dz<2;dz++) for(int dx=0;dx<2;dx++)
                    coverage+=VegetationField.density(seed,wx+(dx+.5)*s/2,wz+(dz+.5)*s/2,p)*.25;
                if(coverage<.22) continue;
                int height=VegetationField.height(seed,wx,wz,p);
                int bottom=surface.ground()+Math.max(2,height/3),top=surface.ground()+height;
                int color=((TextureMaterial.leafForLog(p.logMaterial())+1)<<24)|surface.foliageColor();
                for(int y=Math.floorDiv(bottom,sy);y<=Math.floorDiv(top-1,sy);y++) put(cells,x,y,z,color,side);
            }
        }
        long generated=System.nanoTime(); var out=new ByteArrayOutputStream(); var faces=new GreedyFaceMesher();
        int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
        long[] keys=cells.keySet().stream().mapToLong(Long::longValue).sorted().toArray();
        for(long packed:keys) {
            check(cancelled); int x=(int)(packed&0xfffff)-1,z=(int)((packed>>>20)&0xfffff)-1,y=(int)(packed>>>40)-32768;
            if(x<0 || x>=side || z<0 || z>=side) continue;
            low=Math.min(low,y*sy);high=Math.max(high,(y+1)*sy); int color=cells.get(packed);
            boolean trunk=(color&0x80000000)!=0;
            int material=(color>>>24)&127;
            color&=0xffffff;
            if(trunk) color=0xffffff;
            for(int f=0;f<6;f++) {
                var d=D[f]; if(cells.containsKey(pack(x+d[0],y+d[1],z+d[2]))) continue;
                faces.add(x,y,z,f,material,color);
            }
        }
        faces.emit(out,s,sy,0,0,cancelled);
        stats=new Stats(candidates,samples,cells.size(),out.size()/48,generated-start,System.nanoTime()-generated);
        return new TileMesh(key,out.toByteArray(),new byte[0],new byte[0],low<=high?low:0,low<=high?high:0);
    }
    private static void put(Map<Long,Integer> cells,int x,int y,int z,int color,int side) {
        if(x>=-1 && x<=side && z>=-1 && z<=side) cells.merge(pack(x,y,z),color,Math::min);
    }
    private static long pack(int x,int y,int z) { return ((long)(y+32768)<<40)|((long)(z+1)<<20)|(x+1); }
    private static void check(BooleanSupplier c) { if(c.getAsBoolean()) throw new java.util.concurrent.CancellationException(); }
    private static boolean inside(int x,int z,int pad,int cx,int cz,int radius) {
        double dx=Math.max(0,Math.abs((double)x-cx)-pad),dz=Math.max(0,Math.abs((double)z-cz)-pad);
        return dx*dx+dz*dz<=(double)radius*radius;
    }

}
