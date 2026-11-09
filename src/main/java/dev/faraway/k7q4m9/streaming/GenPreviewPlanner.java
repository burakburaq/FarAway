package dev.faraway.k7q4m9.streaming;

import dev.faraway.k7q4m9.terrain.TileKey;
import java.util.*;

/** Preview-only SOT/LOD experiment; never changes the generation plan. */
public final class GenPreviewPlanner {
    private ScreenSpaceLod projection;
    public void setProjection(ScreenSpaceLod value) { projection=value; }
    private final long seed;
    private final LodScatter.Config scatter;
    public GenPreviewPlanner() { this(0); }
    public GenPreviewPlanner(long seed) { this.seed=seed; this.scatter=null; }
    public GenPreviewPlanner(LodScatter.Config scatter) { this.seed=0; this.scatter=scatter; }
    public record Square(TileKey region,int lod) {}
    private record Candidate(TileKey region,double distance) {}

    public List<Square> plan(Set<TileKey> roots,double x,double y,double z,double minY,double maxY,
                             int screenHeight,double fov,double pixels,int budget,double radius) {
        if(screenHeight<1 || fov<=0 || fov>=180 || pixels<=0 || !Double.isFinite(pixels) || roots.size()>budget || radius<=0) throw new IllegalArgumentException();
        double scale=screenHeight/(2*Math.tan(Math.toRadians(fov)/2));
        var leaves=new LinkedHashSet<TileKey>();
        var queue=new PriorityQueue<Candidate>(Comparator.comparingDouble(Candidate::distance)
            .thenComparingInt(c -> -c.region().spacing()).thenComparingInt(c -> c.region().x()).thenComparingInt(c -> c.region().z()));
        for(var root:roots) if(DirectLodPlanner.intersects(root,x,z,radius)) { leaves.add(root); offer(queue,root,x,y,z,minY,maxY,scale,pixels); }
        while(!queue.isEmpty() && leaves.size()+3<=budget) {
            var node=queue.remove().region(); if(!leaves.remove(node)) continue;
            for(int dz=0;dz<2;dz++) for(int dx=0;dx<2;dx++) {
                var child=node.child(dx,dz); if(!DirectLodPlanner.intersects(child,x,z,radius)) continue;
                leaves.add(child); offer(queue,child,x,y,z,minY,maxY,scale,pixels);
            }
        }
        // Unresolved nodes have no final SOT/LOD; do not show misleading squares.
        for(var pending:queue) leaves.remove(pending.region());
        var result=new ArrayList<Square>(leaves.size());
        for(var node:leaves) {
            int sot=Integer.numberOfTrailingZeros(node.spacing());
            int allowed=allowedLod(node,x,y,z,minY,maxY,scale,pixels);
            result.add(new Square(node,Math.min(allowed,Math.min(18,sot+1))));
        }
        var keys=new HashSet<TileKey>();
        for(var square:result)keys.add(new TileKey(square.region().x(),square.region().z(),1<<square.lod(),square.region().sizeSpacing()));
        result.clear();
        for(var key:DirectLodPlanner.coalesceFarLods(keys))result.add(new Square(new TileKey(key.x(),key.z(),key.sizeSpacing()),Integer.numberOfTrailingZeros(key.spacing())));
        return List.copyOf(result);
    }
    private void offer(PriorityQueue<Candidate> queue,TileKey key,double x,double y,double z,double minY,double maxY,double scale,double pixels) {
        int sot=Integer.numberOfTrailingZeros(key.spacing());
        if(sot>0 && allowedLod(key,x,y,z,minY,maxY,scale,pixels)<sot)
            queue.add(new Candidate(key,distance(key,x,y,z,minY,maxY)));
    }
    private int allowedLod(TileKey key,double x,double y,double z,double minY,double maxY,double scale,double pixels) {
        double allowed=Math.max(1,distance(key,x,y,z,minY,maxY))*pixels/scale;
        if(projection!=null) allowed=projection.allowed(key,pixels,allowed);
        if(scatter!=null) return LodScatter.selectLod(scatter,key,allowed);
        allowed*=LodScatter.multiplier(seed,key);
        return allowed<1?0:Math.min(18,Math.max(0,Math.getExponent(allowed)));
    }
    private static double distance(TileKey key,double x,double y,double z,double minY,double maxY) {
        double dx=axis(x,key.originX(),(double)key.originX()+key.blocks());
        double dy=axis(y,minY,maxY),dz=axis(z,key.originZ(),(double)key.originZ()+key.blocks());
        return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
    private static double axis(double p,double min,double max) { return Math.max(0,Math.max(min-p,p-max)); }

    /** Hide only regions fully displayed at their target LOD, independent of SOT partitioning. */
    public static List<Square> pendingOnly(List<Square> squares,Set<TileKey> displayed) {
        var coveredArea=new HashMap<TileKey,Long>();
        for(var tile:displayed) {
            long area=(long)tile.blocks()*tile.blocks();
            for(var region=tile;;region=new TileKey(Math.floorDiv(region.x(),2),Math.floorDiv(region.z(),2),region.spacing(),region.sizeSpacing()*2)) {
                coveredArea.merge(region,area,Long::sum);if(region.sizeSpacing()==262144)break;
            }
        }
        var pending=new ArrayList<Square>();
        for(var square:squares) {
            var tile=square.region();int spacing=1<<square.lod();
            var target=new TileKey(tile.x(),tile.z(),spacing,tile.sizeSpacing());
            boolean covered=coveredArea.getOrDefault(target,0L)>=(long)target.blocks()*target.blocks();
            for(var region=target;!covered;) {
                if(displayed.contains(region)){covered=true;break;}
                if(region.sizeSpacing()==262144)break;
                region=new TileKey(Math.floorDiv(region.x(),2),Math.floorDiv(region.z(),2),spacing,region.sizeSpacing()*2);
            }
            if(!covered)pending.add(square);
        }
        return List.copyOf(pending);
    }

    /** Retain preview squares intersecting at least one currently missing tile. */
    public static List<Square> missingOnly(List<Square> squares,Collection<TileKey> missing) {
        var missingSet=new HashSet<TileKey>(); for(var key:missing) missingSet.add(new TileKey(key.x(),key.z(),key.sizeSpacing())); var ancestors=new HashSet<TileKey>();
        for(var key:missingSet) for(var k=key;k!=null;k=parent(k)) ancestors.add(k);
        var result=new ArrayList<Square>();
        for(var square:squares) {
            var region=square.region(); boolean overlaps=ancestors.contains(region);
            for(var k=region;!overlaps && k!=null;k=parent(k)) overlaps=missingSet.contains(k);
            if(overlaps) result.add(square);
        }
        return result;
    }
    private static TileKey parent(TileKey key) {
        return key.spacing()>=dev.faraway.k7q4m9.terrain.WorldGrid.SPACING?null:
            new TileKey(Math.floorDiv(key.x(),2),Math.floorDiv(key.z(),2),key.spacing()*2);
    }
}
