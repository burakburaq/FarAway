package dev.faraway.k7q4m9.render;

import java.nio.ByteBuffer;
import java.util.*;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/** Immutable face-plane runs, selected against the gameplay camera before submission. */
public final class FaceSelection {
    private record Plane(float x,float y,float z,float d) {}
    public record Run(int first,int count,float x,float y,float z,float d) {
        public boolean visible(double cx,double cy,double cz) {return x*cx+y*cy+z*cz>=d-0.0001;}
    }
    public record Packed(int[] indices,List<Run> runs) {}
    public static Packed pack(ByteBuffer vertices,int[] indices) {
        var groups=new HashMap<Plane,IntArrayList>();
        for(int i=0;i<indices.length;i+=3) {
            int a=indices[i]*16,b=indices[i+1]*16,c=indices[i+2]*16;
            double ax=vertices.getFloat(a),ay=vertices.getFloat(a+4),az=vertices.getFloat(a+8);
            double ux=vertices.getFloat(b)-ax,uy=vertices.getFloat(b+4)-ay,uz=vertices.getFloat(b+8)-az;
            double vx=vertices.getFloat(c)-ax,vy=vertices.getFloat(c+4)-ay,vz=vertices.getFloat(c+8)-az;
            double nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx,len=Math.sqrt(nx*nx+ny*ny+nz*nz);
            float x=len==0?0:(float)(nx/len),y=len==0?0:(float)(ny/len),z=len==0?0:(float)(nz/len);
            // Canonical positive zero keeps identical axis-aligned faces in one group.
            if(x==0)x=0;if(y==0)y=0;if(z==0)z=0;
            float d=(float)(x*ax+y*ay+z*az);if(d==0)d=0;
            var group=groups.computeIfAbsent(new Plane(x,y,z,d),ignored->new IntArrayList());
            group.add(indices[i]);group.add(indices[i+1]);group.add(indices[i+2]);
        }
        var planes=new ArrayList<>(groups.keySet());
        planes.sort(Comparator.comparingDouble(Plane::x).thenComparingDouble(Plane::y).thenComparingDouble(Plane::z).thenComparingDouble(Plane::d));
        var output=new int[indices.length];var runs=new ArrayList<Run>();int at=0;
        for(var plane:planes) {
            var group=groups.get(plane);runs.add(new Run(at,group.size(),plane.x,plane.y,plane.z,plane.d));
            for(int index:group)output[at++]=index;
        }
        return new Packed(output,List.copyOf(runs));
    }
}
