package dev.faraway.k7q4m9.render;

import net.minecraft.world.phys.AABB;
import org.joml.Matrix4fc;

/** Camera-relative double precision avoids losing distant world coordinates. */
public final class TileVisibility {
    private TileVisibility() {}
    public static boolean entirelyBehind(AABB box,double x,double y,double z,Matrix4fc view) {
        double nx=view.m02(),ny=view.m12(),nz=view.m22();
        double cx=(box.minX+box.maxX)*.5-x,cy=(box.minY+box.maxY)*.5-y,cz=(box.minZ+box.maxZ)*.5-z;
        double radius=Math.abs(nx)*(box.maxX-box.minX)*.5+Math.abs(ny)*(box.maxY-box.minY)*.5+Math.abs(nz)*(box.maxZ-box.minZ)*.5;
        // View space looks along -Z; retain boxes touching the camera plane.
        return nx*cx+ny*cy+nz*cz-radius>0.0625;
    }
    /** Tests only side planes; the far plane is intentionally omitted for world-scale tiles. */
    public static boolean intersectsScreen(AABB box,double x,double y,double z,Matrix4fc clip) {
        double cx=(box.minX+box.maxX)*.5-x,cy=(box.minY+box.maxY)*.5-y,cz=(box.minZ+box.maxZ)*.5-z;
        double ex=(box.maxX-box.minX)*.5,ey=(box.maxY-box.minY)*.5,ez=(box.maxZ-box.minZ)*.5;
        return inside(clip.m03()+clip.m00(),clip.m13()+clip.m10(),clip.m23()+clip.m20(),clip.m33()+clip.m30(),cx,cy,cz,ex,ey,ez)
            && inside(clip.m03()-clip.m00(),clip.m13()-clip.m10(),clip.m23()-clip.m20(),clip.m33()-clip.m30(),cx,cy,cz,ex,ey,ez)
            && inside(clip.m03()+clip.m01(),clip.m13()+clip.m11(),clip.m23()+clip.m21(),clip.m33()+clip.m31(),cx,cy,cz,ex,ey,ez)
            && inside(clip.m03()-clip.m01(),clip.m13()-clip.m11(),clip.m23()-clip.m21(),clip.m33()-clip.m31(),cx,cy,cz,ex,ey,ez);
    }
    /** Only left/right boundaries; caller supplies the tiles already retained by frustum culling. */
    public static boolean touchesHorizontalBoundary(AABB box,double x,double y,double z,Matrix4fc clip) {
        return horizontalBoundaryDirections(box,x,y,z,clip)!=0;
    }
    /** Horizontal directions pointing out of the intersected left/right side planes. */
    public static int horizontalBoundaryDirections(AABB box,double x,double y,double z,Matrix4fc clip) {
        double cx=(box.minX+box.maxX)*.5-x,cy=(box.minY+box.maxY)*.5-y,cz=(box.minZ+box.maxZ)*.5-z;
        double ex=(box.maxX-box.minX)*.5,ey=(box.maxY-box.minY)*.5,ez=(box.maxZ-box.minZ)*.5;
        int directions=0;
        for(int side=-1;side<=1;side+=2){
            double a=clip.m03()+side*clip.m00(),b=clip.m13()+side*clip.m10(),c=clip.m23()+side*clip.m20(),d=clip.m33()+side*clip.m30();
            if(!crosses(a,b,c,d,cx,cy,cz,ex,ey,ez))continue;
            double epsilon=Math.hypot(a,c)*1e-6;
            if(a>epsilon)directions|=TileFootprintEdges.NEG_X;else if(a< -epsilon)directions|=TileFootprintEdges.POS_X;
            if(c>epsilon)directions|=TileFootprintEdges.NEG_Z;else if(c< -epsilon)directions|=TileFootprintEdges.POS_Z;
        }
        return directions;
    }
    private static boolean crosses(double a,double b,double c,double d,double x,double y,double z,double ex,double ey,double ez) {
        double center=a*x+b*y+c*z+d,radius=Math.abs(a)*ex+Math.abs(b)*ey+Math.abs(c)*ez;
        return center-radius<=0.0625&&center+radius>0.0625;
    }
    private static boolean inside(double a,double b,double c,double d,double x,double y,double z,double ex,double ey,double ez) {
        return a*x+b*y+c*z+d+Math.abs(a)*ex+Math.abs(b)*ey+Math.abs(c)*ez>=-0.0625;
    }
}
