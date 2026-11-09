package dev.faraway.k7q4m9.render;

import dev.faraway.k7q4m9.mesh.MeshPerimeter;
import dev.faraway.k7q4m9.terrain.TileKey;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.world.level.ChunkPos;

/** Yellow ground contours, with vertical joins wherever outline vertices share X/Z. */
final class FrustumOutlineMesh {
    private final int originX,originZ,minimumY;
    private final ByteArrayOutputStream out=new ByteArrayOutputStream();
    private final ByteBuffer vertex=ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
    private final Long2ObjectOpenHashMap<HeightRange> corners=new Long2ObjectOpenHashMap<>();

    FrustumOutlineMesh(int originX,int originZ,int minimumY) {
        this.originX=originX;this.originZ=originZ;this.minimumY=minimumY;
    }

    void add(TileKey key,MeshPerimeter perimeter,float flatY) {
        int x0=key.originX(),z0=key.originZ(),x1=x0+key.blocks(),z1=z0+key.blocks();
        int cells=perimeter==null?1:perimeter.cells(),step=key.blocks()/cells;
        for(int side=0;side<4;side++) {
            int x=switch(side) {case MeshPerimeter.EAST,MeshPerimeter.SOUTH -> x1;default -> x0;};
            int z=switch(side) {case MeshPerimeter.SOUTH,MeshPerimeter.WEST -> z1;default -> z0;};
            int dx=side==MeshPerimeter.NORTH?step:side==MeshPerimeter.SOUTH?-step:0;
            int dz=side==MeshPerimeter.EAST?step:side==MeshPerimeter.WEST?-step:0;
            for(int i=0;i<cells;i++) {
                float a=perimeter==null?flatY:perimeter.startY(side,i);
                float b=perimeter==null?flatY:perimeter.endY(side,i);
                float startY=Math.max(a,minimumY),endY=Math.max(b,minimumY);
                point(x,startY,z);
                corner(x,startY,z);
                if(Math.min(a,b)<minimumY && Math.max(a,b)>minimumY) {
                    double t=(minimumY-a)/(double)(b-a);
                    // Preserve the slope above the sea and keep the underwater part flat.
                    point(x+dx*t,minimumY,z+dz*t);
                    point(x+dx*t,minimumY,z+dz*t);
                }
                x+=dx;z+=dz;
                point(x,endY,z);
                corner(x,endY,z);
            }
        }
    }

    private void corner(int x,float y,int z) {
        long key=ChunkPos.pack(x,z);
        var range=corners.get(key);
        if(range==null) corners.put(key,new HeightRange(y));
        else {
            range.low=Math.min(range.low,y);
            range.high=Math.max(range.high,y);
        }
    }

    ByteBuffer finish() {
        for(var entry:corners.long2ObjectEntrySet()) {
            var range=entry.getValue();
            if(range.low==range.high) continue;
            int x=ChunkPos.getX(entry.getLongKey()),z=ChunkPos.getZ(entry.getLongKey());
            point(x,range.low,z);
            point(x,range.high,z);
        }
        return ByteBuffer.wrap(out.toByteArray());
    }

    private void point(double x,float y,double z) {
        vertex.clear();
        vertex.putFloat((float)(x-originX)).putFloat(y).putFloat((float)(z-originZ))
            .put((byte)255).put((byte)255).put((byte)0).put((byte)255);
        out.writeBytes(vertex.array());
    }

    private static final class HeightRange {
        float low,high;
        HeightRange(float y) { low=high=y; }
    }
}
