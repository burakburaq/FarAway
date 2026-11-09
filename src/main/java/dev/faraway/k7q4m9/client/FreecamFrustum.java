package dev.faraway.k7q4m9.client;

import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.ArrayList;

/** Frustum geometry captured when freecam starts; points are relative to origin. */
public final class FreecamFrustum {
    public record Shape(Vec3 origin,List<Vec3> corners,List<Vec3> arrow,double distance,double seaLevel,double worldMin,double worldMax) {}
    public record Geometry(List<Vec3> wire,int frameVertices,Vec3 rightEndpoint) {}
    private FreecamFrustum() {}
    /** Lines are pairs of positions relative to the captured camera. */
    public static List<Vec3> wire(Shape shape) {
        return geometry(shape).wire();
    }
    public static Geometry geometry(Shape shape) {
        var result=new ArrayList<Vec3>(); var c=shape.corners();
        for(var corner:c) { result.add(Vec3.ZERO); result.add(corner); }
        for(double scale:new double[]{0.5,0.75,1.0}) for(int i=0;i<4;i++) {
            result.add(c.get(i).scale(scale)); result.add(c.get((i+1)%4).scale(scale));
        }
        int frameVertices=result.size();
        // Keep each border endpoint while starting the strip at the lower corner.
        double bottom=shape.seaLevel()-shape.origin().y;
        var leftSea=seaIntersection(c.get(3),bottom);
        var rightSea=seaIntersection(c.get(2),bottom);
        boolean seaEndpoints=leftSea!=null && rightSea!=null && (insideWorld(shape,leftSea) || insideWorld(shape,rightSea));
        Vec3 leftEnd=seaEndpoints?leftSea:borderEndpoint(shape,c.get(0),c.get(3));
        Vec3 rightEnd=seaEndpoints?rightSea:borderEndpoint(shape,c.get(1),c.get(2));
        if(leftEnd==null || rightEnd==null) return new Geometry(List.copyOf(result),frameVertices,null);
        double maxHeight=Math.min(1_000_000,Math.hypot(rightEnd.x-leftEnd.x,rightEnd.z-leftEnd.z)*0.04);
        double endTop=bottom+maxHeight;
        Vec3[] stripEnds=new Vec3[2];
        Vec3 drawnRightEnd=null;
        double[] stripEndTops=new double[2];
        {
            for(int sign:new int[]{-1,1}) {
                var lower=c.get(sign<0?0:1); var upper=c.get(sign<0?3:2);
                double stripEndTop=seaEndpoints?Math.min(endTop,bottom+upper.subtract(lower).length()):endTop;
                double top=Math.min(upper.y,stripEndTop);
                double height=top-bottom;
                if(height<=1e-6) continue;
                var endpoint=sign<0?leftEnd:rightEnd;
                double endpointX=endpoint.x,endpointZ=endpoint.z;
                var midpoint=lower;
                double dx=endpointX-midpoint.x,dz=endpointZ-midpoint.z;
                double end=Math.hypot(dx,dz);
                if(end<=1e-6) continue;
                dx/=end; dz/=end;
                double start=0;
                double slope=(stripEndTop-top)/end;
                result.add(new Vec3(midpoint.x+dx*start,bottom,midpoint.z+dz*start)); result.add(new Vec3(midpoint.x+dx*end,bottom,midpoint.z+dz*end));
                result.add(new Vec3(midpoint.x+dx*start,top,midpoint.z+dz*start)); result.add(new Vec3(midpoint.x+dx*end,stripEndTop,midpoint.z+dz*end));
                double t=0;
                for(int lines=0;lines<65536;lines++) {
                    double currentTop=top+slope*t;
                    result.add(new Vec3(midpoint.x+dx*t,bottom,midpoint.z+dz*t)); result.add(new Vec3(midpoint.x+dx*t,currentTop,midpoint.z+dz*t));
                    if(slope>=1) break;
                    double step=(currentTop-bottom)/(slope>0?1-slope:1);
                    if(!Double.isFinite(step) || step<=1e-6 || t+step>=end) break;
                    t+=step;
                }
                result.add(new Vec3(midpoint.x+dx*end,bottom,midpoint.z+dz*end)); result.add(new Vec3(midpoint.x+dx*end,stripEndTop,midpoint.z+dz*end));
                if(sign>0) drawnRightEnd=new Vec3(endpointX,bottom,endpointZ);
                if(seaEndpoints) { int side=sign<0?0:1; stripEnds[side]=new Vec3(endpointX,bottom,endpointZ); stripEndTops[side]=stripEndTop; }
            }
        }
        if(stripEnds[0]!=null && stripEnds[1]!=null && endTop>bottom) {
            var left=stripEnds[0]; var right=stripEnds[1];
            double length=Math.hypot(right.x-left.x,right.z-left.z);
            if(length>1e-6) {
                double height=(stripEndTops[0]+stripEndTops[1])*0.5-bottom,ideal=length/height;
                int low=(int)Math.max(1,Math.min(65536,Math.floor(ideal)));
                int high=Math.min(65536,low+1);
                int cells=Math.abs(length/low-height)<=Math.abs(length/high-height)?low:high;
                result.add(left); result.add(right);
                result.add(new Vec3(left.x,stripEndTops[0],left.z)); result.add(new Vec3(right.x,stripEndTops[1],right.z));
                // End lines already belong to the two side strips.
                for(int i=1;i<cells;i++) {
                    double t=(double)i/cells,x=left.x+(right.x-left.x)*t,z=left.z+(right.z-left.z)*t;
                    result.add(new Vec3(x,bottom,z)); result.add(new Vec3(x,stripEndTops[0]+(stripEndTops[1]-stripEndTops[0])*t,z));
                }
            }
        }
        return new Geometry(List.copyOf(result),frameVertices,drawnRightEnd);
    }
    private static Vec3 borderEndpoint(Shape shape,Vec3 lower,Vec3 upper) {
        double dy=upper.y-lower.y;
        double fraction=Math.abs(dy)<1e-12?(Math.abs(lower.y)<=Math.abs(upper.y)?0:1):Math.max(0,Math.min(1,-lower.y/dy));
        var midpoint=lower.add(upper.subtract(lower).scale(fraction));
        double length=Math.hypot(midpoint.x,midpoint.z);
        if(length<=1e-9) return null;
        double dx=midpoint.x/length,dz=midpoint.z/length;
        double[] interval={0,Double.POSITIVE_INFINITY};
        if(!clip(shape.origin().x+midpoint.x,dx,shape.worldMin(),shape.worldMax(),interval)
            || !clip(shape.origin().z+midpoint.z,dz,shape.worldMin(),shape.worldMax(),interval)
            || interval[0]>1e-6 || !Double.isFinite(interval[1]) || interval[1]<=1e-6) return null;
        return new Vec3(midpoint.x+dx*interval[1],0,midpoint.z+dz*interval[1]);
    }
    private static Vec3 seaIntersection(Vec3 ray,double seaOffset) {
        if(ray.y>=-1e-12) return null;
        double scale=seaOffset/ray.y;
        return scale>0 && Double.isFinite(scale)?ray.scale(scale):null;
    }
    private static boolean insideWorld(Shape shape,Vec3 point) {
        double x=shape.origin().x+point.x,z=shape.origin().z+point.z;
        return x>=shape.worldMin() && x<=shape.worldMax() && z>=shape.worldMin() && z<=shape.worldMax();
    }
    public static Shape create(Vec3 origin,Vec3 forward,Vec3 right,Vec3 up,double fov,double aspect,double sea,double min,double max) {
        double halfHeight=Math.tan(Math.toRadians(fov)/2),halfWidth=halfHeight*aspect;
        Vec3 lowerLeft=forward.add(right.scale(-halfWidth)).add(up.scale(-halfHeight));
        Vec3 lowerRight=forward.add(right.scale(halfWidth)).add(up.scale(-halfHeight));
        double leftDistance=10_000_000,rightDistance=10_000_000;
        if(Math.abs(lowerLeft.y)>1e-12 && Math.abs(lowerRight.y)>1e-12) {
            double left=(sea-origin.y)/lowerLeft.y,rightDistanceCandidate=(sea-origin.y)/lowerRight.y;
            if(left>0 && rightDistanceCandidate>0 && Double.isFinite(left) && Double.isFinite(rightDistanceCandidate)) {
                var a=origin.add(lowerLeft.scale(left)); var b=origin.add(lowerRight.scale(rightDistanceCandidate));
                if(intersects(a.x,a.z,b.x,b.z,min,max)) { leftDistance=left; rightDistance=rightDistanceCandidate; }
            }
        }
        var upperLeft=forward.add(right.scale(-halfWidth)).add(up.scale(halfHeight));
        var upperRight=forward.add(right.scale(halfWidth)).add(up.scale(halfHeight));
        var corners=List.of(lowerLeft.scale(leftDistance),lowerRight.scale(rightDistance),upperRight.scale(rightDistance),upperLeft.scale(leftDistance));
        var base=forward.scale(2).add(up.scale(2*halfHeight));
        var arrow=List.of(base.add(right.scale(-0.35)),base.add(right.scale(0.35)),base.add(up.scale(0.8)));
        return new Shape(origin,corners,arrow,Math.max(leftDistance,rightDistance),sea,min,max);
    }
    static boolean intersects(double x1,double z1,double x2,double z2,double min,double max) {
        double[] interval={0,1};
        return clip(x1,x2-x1,min,max,interval) && clip(z1,z2-z1,min,max,interval);
    }
    private static boolean clip(double start,double delta,double min,double max,double[] interval) {
        if(Math.abs(delta)<1e-12) return start>=min && start<=max;
        double a=(min-start)/delta,b=(max-start)/delta;
        interval[0]=Math.max(interval[0],Math.min(a,b)); interval[1]=Math.min(interval[1],Math.max(a,b));
        return interval[0]<=interval[1];
    }
}
