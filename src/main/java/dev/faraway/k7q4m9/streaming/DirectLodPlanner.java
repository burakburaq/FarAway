package dev.faraway.k7q4m9.streaming;

import dev.faraway.k7q4m9.terrain.TileKey;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.List;

/** Plans leaf tiles directly from projected sample spacing, without generating ancestors. */
public final class DirectLodPlanner {
    private ScreenSpaceLod projection;
    public void setProjection(ScreenSpaceLod value) { projection=value; }
    private final java.util.Map<TileKey,Integer> history=new java.util.HashMap<>();
    private int lowerTolerance,upperTolerance;
    public void setHistory(Set<TileKey> previous,int lower,int upper) {
        lowerTolerance=lower;upperTolerance=upper;history.clear();
        for(var tile:previous) {
            int lod=Integer.numberOfTrailingZeros(tile.spacing());
            for(var region=new TileKey(tile.x(),tile.z(),tile.sizeSpacing());;region=new TileKey(Math.floorDiv(region.x(),2),Math.floorDiv(region.z(),2),region.sizeSpacing()*2)) {
                history.merge(region,lod,Math::min);if(region.sizeSpacing()==262144)break;
            }
        }
    }
    public static int hysteresisLevel(double allowed,int previous,int lower,int upper) {
        int raw=level(allowed);
        return raw<previous?Math.min(previous,level(allowed*(1+upper/100.0))):raw>previous?Math.max(previous,level(allowed*(1-lower/100.0))):previous;
    }
    private static int level(double allowed) { return allowed<1?0:Math.min(18,Math.max(0,Math.getExponent(allowed))); }
    private final long seed;
    private final LodScatter.Config scatter;
    public DirectLodPlanner() { this(0); }
    public DirectLodPlanner(long seed) { this.seed=seed; this.scatter=null; }
    public DirectLodPlanner(LodScatter.Config scatter) { this.seed=0; this.scatter=scatter; }
    public record Plan(Set<TileKey> leaves, Set<TileKey> deferred, List<TileKey> nearestFirst, boolean budgetLimited) {}
    private record Candidate(TileKey key,double distance) {}

    public Plan plan(Set<TileKey> roots,double x,double y,double z,double minY,double maxY,
                     int screenHeight,double fov,double samplePixels,int tileBudget) {
        return plan(roots,x,y,z,minY,maxY,screenHeight,fov,samplePixels,tileBudget,Double.POSITIVE_INFINITY);
    }
    public Plan plan(Set<TileKey> roots,double x,double y,double z,double minY,double maxY,
                     int screenHeight,double fov,double samplePixels,int tileBudget,double radiusBlocks) {
        if(screenHeight<1 || fov<=0 || fov>=180 || !Double.isFinite(samplePixels) || samplePixels<=0 || roots.size()>tileBudget)
            throw new IllegalArgumentException("Invalid direct LOD plan parameters");
        if(Double.isNaN(radiusBlocks) || radiusBlocks<=0) throw new IllegalArgumentException("Invalid render radius");
        double scale=screenHeight/(2*Math.tan(Math.toRadians(fov)/2));
        var leaves=new LinkedHashSet<TileKey>();
        for(var root:roots) if(intersects(root,x,z,radiusBlocks)) leaves.add(root);
        var candidates=new PriorityQueue<Candidate>(Comparator.comparingDouble(Candidate::distance)
            .thenComparingInt(c -> -c.key().spacing()).thenComparingInt(c -> c.key().x()).thenComparingInt(c -> c.key().z()));
        for(var root:leaves) offer(candidates,root,x,y,z,minY,maxY,scale,samplePixels);
        while(!candidates.isEmpty() && leaves.size()+3<=tileBudget) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            var parent=candidates.remove().key();
            if(!leaves.remove(parent)) continue;
            for(int dz=0;dz<2;dz++) for(int dx=0;dx<2;dx++) {
                var child=parent.child(dx,dz);
                if(!intersects(child,x,z,radiusBlocks)) continue;
                leaves.add(child);
                offer(candidates,child,x,y,z,minY,maxY,scale,samplePixels);
            }
        }
        // Unresolved candidates have not reached their requested quality. Leave them
        // pending rather than generating an intermediate-resolution mesh.
        boolean budgetLimited=!candidates.isEmpty();
        var deferred=new LinkedHashSet<TileKey>();
        for(var candidate:candidates) { deferred.add(candidate.key()); leaves.remove(candidate.key()); }
        var actual=new LinkedHashSet<TileKey>();
        for(var region:leaves) {
            int sizeLevel=Integer.numberOfTrailingZeros(region.sizeSpacing());
            int lod=Math.min(18,Math.min(sizeLevel+1,allowedLod(region,x,y,z,minY,maxY,scale,samplePixels)));
            actual.add(new TileKey(region.x(),region.z(),1<<lod,region.sizeSpacing()));
        }
        leaves=new LinkedHashSet<>(coalesceFarLods(actual));
        var ordered=new java.util.ArrayList<>(leaves);
        ordered.sort(Comparator.comparingDouble((TileKey key) -> distance(key,x,y,z,minY,maxY))
            .thenComparingInt(TileKey::spacing).thenComparingInt(TileKey::x).thenComparingInt(TileKey::z));
        return new Plan(Set.copyOf(leaves),Set.copyOf(deferred),List.copyOf(ordered),budgetLimited);
    }
    public static Set<TileKey> coalesceFarLods(Set<TileKey> input) {
        var near=new java.util.HashSet<TileKey>();var far=new java.util.HashSet<TileKey>();
        for(var key:input)(key.spacing()<16?near:far).add(key);
        near.addAll(coalesceSameLod(far,64));return Set.copyOf(near);
    }
    /** Merge complete sibling quads without changing their sample spacing or covered area. */
    public static Set<TileKey> coalesceSameLod(Set<TileKey> input,int maxSamplesPerSide) {
        var result=new java.util.HashSet<>(input);
        boolean changed;
        do {
            changed=false;
            var ordered=new java.util.ArrayList<>(result);
            ordered.sort(Comparator.comparingInt(TileKey::sizeSpacing).thenComparingInt(TileKey::spacing).thenComparingInt(TileKey::x).thenComparingInt(TileKey::z));
            for(var tile:ordered) {
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
                if(tile.sizeSpacing()>=262144 || (long)tile.blocks()*2/tile.spacing()>maxSamplesPerSide)continue;
                int px=Math.floorDiv(tile.x(),2),pz=Math.floorDiv(tile.z(),2);
                var siblings=new TileKey[4];boolean complete=true;
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++) {
                    var sibling=new TileKey(px*2+dx,pz*2+dz,tile.spacing(),tile.sizeSpacing());siblings[dz*2+dx]=sibling;
                    if(!result.contains(sibling))complete=false;
                }
                if(!complete)continue;
                for(var sibling:siblings)result.remove(sibling);
                result.add(new TileKey(px,pz,tile.spacing(),tile.sizeSpacing()*2));changed=true;
            }
        } while(changed);
        return Set.copyOf(result);
    }
    private void offer(PriorityQueue<Candidate> queue,TileKey key,double x,double y,double z,
                              double minY,double maxY,double scale,double pixels) {
        if(key.sizeSpacing()<=1) return;
        if(allowedLod(key,x,y,z,minY,maxY,scale,pixels)<Integer.numberOfTrailingZeros(key.sizeSpacing())) queue.add(new Candidate(key,distance(key,x,y,z,minY,maxY)));
    }
    private int allowedLod(TileKey key,double x,double y,double z,double minY,double maxY,double scale,double pixels) {
        double allowed=Math.max(1,distance(key,x,y,z,minY,maxY))*pixels/scale;
        if(projection!=null) allowed=projection.allowed(key,pixels,allowed);
        int selected=select(key,allowed);
        if(projection==null || history.isEmpty())return selected;
        Integer previous=null;
        for(var region=new TileKey(key.x(),key.z(),key.sizeSpacing());;region=new TileKey(Math.floorDiv(region.x(),2),Math.floorDiv(region.z(),2),region.sizeSpacing()*2)) {
            previous=history.get(region);if(previous!=null || region.sizeSpacing()==262144)break;
        }
        if(previous==null)return selected;
        return selected<previous?Math.min(previous,select(key,allowed*(1+upperTolerance/100.0))):selected>previous?Math.max(previous,select(key,allowed*(1-lowerTolerance/100.0))):selected;
    }
    private int select(TileKey key,double allowed) { return scatter!=null?LodScatter.selectLod(scatter,key,allowed):level(allowed*LodScatter.multiplier(seed,key)); }

    private static double distance(TileKey key,double x,double y,double z,double minY,double maxY) {
        double dx=axis(x,key.originX(),(double)key.originX()+key.blocks());
        double dy=axis(y,minY,maxY);
        double dz=axis(z,key.originZ(),(double)key.originZ()+key.blocks());
        return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
    private static double axis(double p,double min,double max) { return Math.max(0,Math.max(min-p,p-max)); }
    public static boolean intersects(TileKey key,double x,double z,double radius) {
        double dx=axis(x,key.originX(),(double)key.originX()+key.blocks());
        double dz=axis(z,key.originZ(),(double)key.originZ()+key.blocks());
        return dx*dx+dz*dz<=radius*radius;
    }
}
