package dev.faraway.k7q4m9.streaming;

import dev.faraway.k7q4m9.terrain.TileKey;
import dev.faraway.k7q4m9.terrain.WorldGrid;
import java.util.Set;
import java.util.LinkedHashSet;

/** Constant-size axis-boundary probes; never traverses the planned tile set. */
public final class BenchmarkBoundarySelection {
    private BenchmarkBoundarySelection() {}
    public static Set<TileKey> select(Set<TileKey> targets,int x,int y,int z,int minY,int maxY,
                                      int height,double fov,double pixels,double renderRadius) {
        return select(targets,x,y,z,minY,maxY,height,fov,pixels,renderRadius,new LodScatter.Config(15,4,0));
    }
    public static Set<TileKey> select(Set<TileKey> targets,int x,int y,int z,int minY,int maxY,
                                      int height,double fov,double pixels,double renderRadius,LodScatter.Config scatter) {
        var selected=new LinkedHashSet<TileKey>();
        double scale=height/(2*Math.tan(Math.toRadians(fov)/2));
        double dy=Math.max(0,Math.max(minY-y,y-maxY));
        for(int lod=0;lod<=18;lod++) {
            int spacing=1<<lod;
            TileKey[] chosen=new TileKey[4];
            for(int sizeLevel=Math.max(0,lod-1);sizeLevel<=Math.min(18,lod+1);sizeLevel++) {
            int sizeSpacing=1<<sizeLevel,b=32*sizeSpacing;
            // East/north, north/west, west/south, south/east in map coordinates.
            for(int quadrant=0;quadrant<4;quadrant++) {
                boolean alongX=(quadrant&1)==0,positive=quadrant==0 || quadrant==3;
                int center=alongX?x:z,cross=alongX?z:x;
                boolean crossPositive=quadrant==2 || quadrant==3;
                int row=Math.floorDiv(cross-(crossPositive?0:1),b);
                long parentCross=(long)Math.floorDiv(row,2)*2*b;
                double crossDistance=Math.max(0,Math.max(parentCross-cross,cross-(parentCross+2L*b)));
                double r=2*Math.min(sizeSpacing,spacing)*scale/pixels;
                double minMultiplier=1+(scatter.enabled()?scatter.strength():0)*scatter.noiseBottom()/10000.0;
                double maxMultiplier=1+(scatter.enabled()?scatter.strength():0)*scatter.noiseTop()/10000.0;
                if(scatter.mode()==1) {
                    double low=Math.max(-1,Math.min(1,scatter.noiseBottom()/50.0));
                    double high=Math.max(-1,Math.min(1,scatter.noiseTop()/50.0));
                    double minRatio=1+(scatter.enabled()?scatter.strength():0)/100.0*low,maxRatio=1+(scatter.enabled()?scatter.strength():0)/100.0*high;
                    minMultiplier=1/maxRatio;
                    maxMultiplier=minRatio<=0?Double.POSITIVE_INFINITY:1/minRatio;
                }
                double outerRadius=minMultiplier<=0?Double.POSITIVE_INFINITY:r/minMultiplier;
                double squared=outerRadius*outerRadius-dy*dy-crossDistance*crossDistance;
                if(sizeLevel<18 && squared<=0) continue;
                double reach=sizeLevel==18?Double.POSITIVE_INFINITY:Math.min(renderRadius,Math.sqrt(squared));
                long parent=(long)Math.floor((center+(positive?Math.nextDown(reach):-reach))/(2.0*b));
                long outer=positive?2*parent+1:2*parent;
                int worldMin=Math.floorDiv(WorldGrid.WORLD_MIN,WorldGrid.TILE_BLOCKS)*WorldGrid.TILE_BLOCKS;
                int worldMax=(WorldGrid.MAX_TILE+1)*WorldGrid.TILE_BLOCKS;
                long clip=positive?(long)Math.floor(Math.min(center+renderRadius,worldMax-1)/(double)b)
                    :(long)Math.ceil(Math.max(center-renderRadius,worldMin)/(double)b)-1;
                outer=sizeLevel==18?clip:positive?Math.min(outer,clip):Math.max(outer,clip);
                double innerRadius=maxMultiplier<=0?Double.POSITIVE_INFINITY:r/maxMultiplier;
                double innerSquared=innerRadius*innerRadius-dy*dy-crossDistance*crossDistance;
                double innerReach=innerSquared>0?Math.sqrt(innerSquared):0;
                double searchReach=Math.min(renderRadius,reach);
                int probes=4+(int)Math.min(8192,Math.ceil(Math.max(0,searchReach-innerReach)/b));
                for(int adjustment=0;adjustment<probes;adjustment++) {
                    long axis=outer+(positive?-adjustment:adjustment);
                    if(axis<Integer.MIN_VALUE || axis>Integer.MAX_VALUE) continue;
                    var key=alongX?new TileKey((int)axis,row,spacing,sizeSpacing):new TileKey(row,(int)axis,spacing,sizeSpacing);
                    if(targets.contains(key)) {
                        var old=chosen[quadrant];
                        long position=alongX?key.originX():key.originZ();
                        long oldPosition=old==null?0:alongX?old.originX():old.originZ();
                        if(old==null || (positive?position+key.blocks()>oldPosition+old.blocks():position<oldPosition)) chosen[quadrant]=key;
                        break;
                    }
                }
            }
            }
            for(var key:chosen) if(key!=null) selected.add(key);
        }
        return Set.copyOf(selected);
    }
}
