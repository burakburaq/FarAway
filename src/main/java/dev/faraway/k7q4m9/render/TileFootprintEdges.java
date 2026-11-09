package dev.faraway.k7q4m9.render;

import dev.faraway.k7q4m9.terrain.TileKey;
import java.util.*;

/** Neighbour coverage of retained tile footprints, including unequal tile sizes. */
public final class TileFootprintEdges {
    public static final int NEG_X=1,POS_X=2,NEG_Z=4,POS_Z=8;
    private record Span(long from,long to) {}
    private final Map<Long,List<Span>> left=new HashMap<>(),right=new HashMap<>(),bottom=new HashMap<>(),top=new HashMap<>();
    public TileFootprintEdges(List<TileKey> keys){
        for(var key:keys){long x=key.originX(),z=key.originZ(),b=key.blocks();
            add(left,x,z,z+b);add(right,x+b,z,z+b);add(bottom,z,x,x+b);add(top,z+b,x,x+b);
        }
        for(var map:List.of(left,right,bottom,top))for(var spans:map.values())spans.sort(Comparator.comparingLong(Span::from));
    }
    private static void add(Map<Long,List<Span>> map,long at,long from,long to){map.computeIfAbsent(at,k->new ArrayList<>()).add(new Span(from,to));}
    public boolean hasExposedEdge(TileKey key,int directions){
        long x=key.originX(),z=key.originZ(),b=key.blocks();
        return ((directions&NEG_X)!=0&&!covered(right.get(x),z,z+b))
            ||((directions&POS_X)!=0&&!covered(left.get(x+b),z,z+b))
            ||((directions&NEG_Z)!=0&&!covered(top.get(z),x,x+b))
            ||((directions&POS_Z)!=0&&!covered(bottom.get(z+b),x,x+b));
    }
    private static boolean covered(List<Span> spans,long from,long to){
        if(spans==null)return false;long cursor=from;
        for(var span:spans){if(span.to<=cursor)continue;if(span.from>cursor)return false;cursor=Math.max(cursor,span.to);if(cursor>=to)return true;}
        return false;
    }
}
