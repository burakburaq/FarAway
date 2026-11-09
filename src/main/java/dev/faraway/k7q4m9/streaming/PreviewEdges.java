package dev.faraway.k7q4m9.streaming;

import java.util.*;

/** Extract boundaries from a non-overlapping quadtree, including unequal-size neighbours. */
public final class PreviewEdges {
    public record Edge(int x1,int z1,int x2,int z2,boolean outer) {}
    private record Line(boolean vertical,int coordinate) {}
    private record Event(int at,int side,int lod,boolean start) {}
    private PreviewEdges() {}
    public static List<Edge> build(List<GenPreviewPlanner.Square> squares,int minLod,int maxLod) {
        var lines=new HashMap<Line,ArrayList<Event>>();
        for(var square:squares) {
            if(square.lod()<minLod || square.lod()>maxLod) continue;
            var key=square.region(); int x=key.originX(),z=key.originZ(),right=Math.addExact(x,key.blocks()),bottom=Math.addExact(z,key.blocks());
            add(lines,new Line(true,x),z,bottom,1,square.lod()); add(lines,new Line(true,right),z,bottom,0,square.lod());
            add(lines,new Line(false,z),x,right,1,square.lod()); add(lines,new Line(false,bottom),x,right,0,square.lod());
        }
        var result=new ArrayList<Edge>();
        for(var entry:lines.entrySet()) {
            var events=entry.getValue(); events.sort(Comparator.comparingInt(Event::at));
            int[] lod={-1,-1}; int previous=events.get(0).at(),i=0;
            while(i<events.size()) {
                int at=events.get(i).at();
                boolean outer=(lod[0]<0)!=(lod[1]<0),transition=lod[0]>=0 && lod[1]>=0 && lod[0]!=lod[1];
                if(at>previous && (outer || transition)) {
                    var line=entry.getKey(); result.add(line.vertical()?new Edge(line.coordinate(),previous,line.coordinate(),at,outer):new Edge(previous,line.coordinate(),at,line.coordinate(),outer));
                }
                int end=i; while(end<events.size() && events.get(end).at()==at) end++;
                for(int j=i;j<end;j++) { var event=events.get(j); if(!event.start()) lod[event.side()]=-1; }
                for(int j=i;j<end;j++) { var event=events.get(j); if(event.start()) lod[event.side()]=event.lod(); }
                previous=at; i=end;
            }
        }
        return List.copyOf(result);
    }
    private static void add(Map<Line,ArrayList<Event>> lines,Line line,int from,int to,int side,int lod) {
        var events=lines.computeIfAbsent(line,k -> new ArrayList<>()); events.add(new Event(from,side,lod,true)); events.add(new Event(to,side,lod,false));
    }
}
