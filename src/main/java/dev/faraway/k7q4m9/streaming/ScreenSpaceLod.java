package dev.faraway.k7q4m9.streaming;

import dev.faraway.k7q4m9.terrain.TileKey;
import org.joml.Matrix4f;

/** Four sea-plane corner Jacobians. No terrain sampling or voxel projection. */
public final class ScreenSpaceLod {
    private final Matrix4f m;
    private final double x,y,z,sea;
    private final int width,height;
    public ScreenSpaceLod(Matrix4f matrix,double x,double y,double z,double sea,int width,int height) {
        m=new Matrix4f(matrix);this.x=x;this.y=y;this.z=z;this.sea=sea;this.width=width;this.height=height;
    }
    public boolean sameView(ScreenSpaceLod other) {
        return other!=null && width==other.width && height==other.height && sea==other.sea && m.equals(other.m,0.00001f);
    }
    public double allowed(TileKey key,double pixels,double fallback) {
        double max=0;int behind=0,left=0,right=0,top=0,bottom=0;boolean invalid=false;
        for(int i=0;i<4;i++) {
            double px=key.originX()+((i&1)==0?0:key.blocks())-x,py=sea-y,pz=key.originZ()+((i&2)==0?0:key.blocks())-z;
            double u=m.m00()*px+m.m10()*py+m.m20()*pz+m.m30();
            double v=m.m01()*px+m.m11()*py+m.m21()*pz+m.m31();
            double w=m.m03()*px+m.m13()*py+m.m23()*pz+m.m33();
            if(w<=0)behind++;
            if(u< -w)left++;if(u>w)right++;if(v< -w)bottom++;if(v>w)top++;
            if(!Double.isFinite(w)||w<=0.1) {invalid=true;continue;}
            double ux=(m.m00()*w-u*m.m03())/(w*w)*width*.5;
            double uz=(m.m20()*w-u*m.m23())/(w*w)*width*.5;
            double vx=(m.m01()*w-v*m.m03())/(w*w)*height*.5;
            double vz=(m.m21()*w-v*m.m23())/(w*w)*height*.5;
            double k=Math.max(Math.abs(ux)+Math.abs(uz),Math.abs(vx)+Math.abs(vz));
            if(!Double.isFinite(k))invalid=true;else max=Math.max(max,k);
        }
        // Offscreen regions retain the normal radial plan instead of refining the entire world behind the eye.
        if(behind==4 || left==4 || right==4 || top==4 || bottom==4)return fallback;
        if(invalid)return 0;
        return max>0?pixels/max:fallback;
    }
}
