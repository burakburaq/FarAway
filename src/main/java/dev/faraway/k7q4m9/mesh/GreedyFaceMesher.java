package dev.faraway.k7q4m9.mesh;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.function.BooleanSupplier;

/** Merges coplanar, equally shaded voxel faces without changing their material. */
final class GreedyFaceMesher {
    private static final int[][][] F={{{0,0,1},{0,1,1},{0,0,0},{0,1,0}},{{1,0,0},{1,1,0},{1,0,1},{1,1,1}},
        {{0,0,0},{1,0,0},{0,0,1},{1,0,1}},{{0,1,1},{1,1,1},{0,1,0},{1,1,0}},
        {{0,0,0},{0,1,0},{1,0,0},{1,1,0}},{{1,0,1},{1,1,1},{0,0,1},{0,1,1}}};
    private static final int[] TRI={0,1,2,2,1,3};
    private record Group(int face,int plane,int material,int color) {}
    private final HashMap<Group,HashSet<Long>> groups=new HashMap<>();
    void add(int x,int y,int z,int face,int material,int color) {
        int plane=face<2?x:face<4?y:z;
        int u=face<2?z:x, v=face<2 || face>=4?y:z;
        groups.computeIfAbsent(new Group(face,plane,material,color),ignored -> new HashSet<>()).add(pack(u,v));
    }
    void emit(ByteArrayOutputStream out,int stepX,int stepY,int originX,int originZ,BooleanSupplier cancelled) {
        var vertex=ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        for(var entry:groups.entrySet()) {
            var g=entry.getKey(); var cells=entry.getValue();
            while(!cells.isEmpty()) {
                if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                long first=cells.iterator().next(); int u=(int)(first>>32),v=(int)first,w=1,h=1;
                while(cells.contains(pack(u+w,v))) w++;
                outer:while(true) { for(int j=0;j<w;j++) if(!cells.contains(pack(u+j,v+h))) break outer; h++; }
                for(int j=0;j<h;j++) for(int i=0;i<w;i++) cells.remove(pack(u+i,v+j));
                int f=g.face(),x=f<2?g.plane():u,y=f<2 || f>=4?v:g.plane(),z=f<2?u:f>=4?g.plane():v;
                int dx=f<2?1:w,dy=f<2 || f>=4?h:1,dz=f<2?w:f>=4?1:h;
                float shade=f==3?1:f==2?.5f:f<2?.72f:.86f;
                int color=g.color();
                for(int corner:TRI) {
                    var c=F[f][corner]; vertex.clear();
                    vertex.putFloat((x+c[0]*dx)*(float)stepX-originX).putFloat((y+c[1]*dy)*(float)stepY)
                        .putFloat((z+c[2]*dz)*(float)stepX-originZ)
                        .put((byte)Math.round((color>>16&255)*shade)).put((byte)Math.round((color>>8&255)*shade))
                        .put((byte)Math.round((color&255)*shade)).put((byte)g.material());
                    out.writeBytes(vertex.array());
                }
            }
        }
    }
    private static long pack(int u,int v) { return ((long)u<<32)|(v&0xffffffffL); }
}
