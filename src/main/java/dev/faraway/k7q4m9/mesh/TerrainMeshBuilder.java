package dev.faraway.k7q4m9.mesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import dev.faraway.k7q4m9.terrain.LodTile;
import dev.faraway.k7q4m9.terrain.TileKey;

public final class TerrainMeshBuilder {
    public TileMesh build(LodTile tile, BooleanSupplier cancelled) {
        int cells=tile.cells(),side=tile.verticesPerSide();
        ByteBuffer vertices = buffer((side*side + cells*cells*4 + cells*16) * 16);
        ByteBuffer indices = buffer((cells*cells*12 + cells*24) * 2);
        ByteBuffer water = buffer((cells*cells*12 + cells*24) * 16);
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
            float nx = (tile.haloHeight(x - 1, z) - tile.haloHeight(x + 1, z)) / (2f * tile.key().spacing());
            float nz = (tile.haloHeight(x, z - 1) - tile.haloHeight(x, z + 1)) / (2f * tile.key().spacing());
            double length = Math.sqrt(nx * nx + nz * nz + 1);
            float light = (float)(.55 + .45 * Math.max(0, (nx * .35 + .87 + nz * .35) / length));
            emit(vertices, x * tile.key().spacing(), tile.height(x,z), z * tile.key().spacing(), shade(tile.material(x,z).terrainColor(), light));
        }
        for (int z = 0; z < cells; z++) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            for (int x = 0; x < cells; x++) {
                int a = z * side + x, b = a + 1, c = a + side, d = c + 1;
                triangle(vertices, indices, tile, a, c, b);
                triangle(vertices, indices, tile, b, c, d);
                waterTriangle(water, tile, a, c, b);
                waterTriangle(water, tile, b, c, d);
            }
        }
        // Downward edge curtains cover the height mismatch between independent LOD grids.
        // They do not alter sample quality or require regenerating a neighboring tile.
        var perimeter=new MeshPerimeter(cells);
        for(int i=0;i<cells;i++) {
            skirt(vertices,indices,tile,perimeter,MeshPerimeter.NORTH,i,i,0,i+1,0);
            skirt(vertices,indices,tile,perimeter,MeshPerimeter.EAST,i,cells,i,cells,i+1);
            skirt(vertices,indices,tile,perimeter,MeshPerimeter.SOUTH,i,cells-i,cells,cells-1-i,cells);
            skirt(vertices,indices,tile,perimeter,MeshPerimeter.WEST,i,0,cells-i,0,cells-1-i);
        }
        return new TileMesh(tile.key(), indices.position()==0?new byte[0]:Arrays.copyOf(vertices.array(),vertices.position()), Arrays.copyOf(indices.array(), indices.position()),
            Arrays.copyOf(water.array(), water.position()), -64, tile.maxY()).withPerimeter(perimeter);
    }
    private static void skirt(ByteBuffer vertices,ByteBuffer indices,LodTile tile,MeshPerimeter perimeter,
                              int side,int cell,int ax,int az,int bx,int bz) {
        perimeter.set(side,cell,tile.wet(ax,az)?tile.waterHeight(ax,az):tile.height(ax,az),
            tile.wet(bx,bz)?tile.waterHeight(bx,bz):tile.height(bx,bz));
        if(!tile.terrain(ax,az) || !tile.terrain(bx,bz) || tile.wet(ax,az) || tile.wet(bx,bz)) return;
        float aX=ax*tile.key().spacing(),aZ=az*tile.key().spacing(),bX=bx*tile.key().spacing(),bZ=bz*tile.key().spacing();
        int ox=az==bz?0:ax==0?-1:1,oz=ax==bx?0:az==0?-1:1;
        int floor=-64;
        if(tile.haloWet(ax+ox,az+oz)) floor=Math.max(floor,tile.haloWaterHeight(ax+ox,az+oz));
        if(tile.haloWet(bx+ox,bz+oz)) floor=Math.max(floor,tile.haloWaterHeight(bx+ox,bz+oz));
        if(Math.min(tile.height(ax,az),tile.height(bx,bz))<=floor) return;
        perimeter.set(side,cell,floor,floor);
        int base=vertices.position()/16;
        emit(vertices,aX,tile.height(ax,az),aZ,tile.material(ax,az).terrainColor());
        emit(vertices,bX,tile.height(bx,bz),bZ,tile.material(bx,bz).terrainColor());
        emit(vertices,aX,floor,aZ,tile.material(ax,az).terrainColor());
        emit(vertices,bX,floor,bZ,tile.material(bx,bz).terrainColor());
        for(int n:new int[]{0,1,2,1,3,2}) indices.putShort((short)(base+n));
    }
    private static void triangle(ByteBuffer vertices,ByteBuffer out,LodTile tile,int a,int b,int c) {
        if(!tile.terrain(a%tile.verticesPerSide(),a/tile.verticesPerSide()) || !tile.terrain(b%tile.verticesPerSide(),b/tile.verticesPerSide()) || !tile.terrain(c%tile.verticesPerSide(),c/tile.verticesPerSide())) return;
        int[] ids={a,b,c}; float level=0; int wet=0;
        for(int id:ids) if(tile.wet(id%tile.verticesPerSide(),id/tile.verticesPerSide())) { level+=tile.waterHeight(id%tile.verticesPerSide(),id/tile.verticesPerSide()); wet++; }
        if(wet==3) return; // No seabed geometry underneath opaque water.
        if(wet==0) { out.putShort((short)a).putShort((short)b).putShort((short)c); return; }
        level/=wet;
        float[] depth=new float[3];
        for(int i=0;i<3;i++) {
            int x=ids[i]%tile.verticesPerSide(),z=ids[i]/tile.verticesPerSide();
            depth[i]=tile.wet(x,z)?tile.height(x,z)-tile.waterHeight(x,z):Math.max(.01f,tile.height(x,z)-level);
        }
        int[] polygon=new int[4]; int count=0;
        for(int i=0;i<3;i++) {
            int prev=(i+2)%3;
            if((depth[prev]>0)!=(depth[i]>0)) {
                float t=depth[prev]/(depth[prev]-depth[i]);
                int pa=ids[prev]*16,pb=ids[i]*16,newIndex=vertices.position()/16;
                float x=vertices.getFloat(pa)+(vertices.getFloat(pb)-vertices.getFloat(pa))*t;
                float z=vertices.getFloat(pa+8)+(vertices.getFloat(pb+8)-vertices.getFloat(pa+8))*t;
                int color=0;
                for(int ch=0;ch<3;ch++) color=(color<<8)|Math.round(Byte.toUnsignedInt(vertices.get(pa+12+ch))+(Byte.toUnsignedInt(vertices.get(pb+12+ch))-Byte.toUnsignedInt(vertices.get(pa+12+ch)))*t);
                emit(vertices,x,level,z,color); polygon[count++]=newIndex;
            }
            if(depth[i]>0) polygon[count++]=ids[i];
        }
        for(int i=1;i+1<count;i++) out.putShort((short)polygon[0]).putShort((short)polygon[i]).putShort((short)polygon[i+1]);
    }
    private static void waterTriangle(ByteBuffer out, LodTile tile, int a, int b, int c) {
        int[] ids = {a,b,c};
        float level = 0;
        int wet = 0, color = 0;
        for (int id : ids) if (tile.wet(id%tile.verticesPerSide(),id/tile.verticesPerSide())) {
            level += tile.waterHeight(id%tile.verticesPerSide(),id/tile.verticesPerSide()); wet++; color = tile.material(id%tile.verticesPerSide(),id/tile.verticesPerSide()).waterColor();
        }
        if (wet == 0) return;
        level /= wet;
        Point[] input = new Point[3];
        for (int i = 0; i < 3; i++) {
            int x = ids[i]%tile.verticesPerSide(), z = ids[i]/tile.verticesPerSide();
            float surface = tile.wet(x,z) ? tile.waterHeight(x,z) : level;
            float depth = tile.wet(x,z) ? surface - tile.height(x,z) : Math.min(-.01f, surface - tile.height(x,z));
            input[i] = new Point(x*tile.key().spacing(), surface, z*tile.key().spacing(), depth);
        }
        Point[] clipped = new Point[4];
        int count = 0;
        for (int i = 0; i < 3; i++) {
            Point prev = input[(i+2)%3], current = input[i];
            if ((prev.depth > 0) != (current.depth > 0)) {
                float t = prev.depth / (prev.depth-current.depth);
                clipped[count++] = new Point(prev.x+(current.x-prev.x)*t, prev.y+(current.y-prev.y)*t,
                    prev.z+(current.z-prev.z)*t, 0);
            }
            if (current.depth > 0) clipped[count++] = current;
        }
        for (int i = 1; i+1 < count; i++) {
            for (Point p : new Point[]{clipped[0],clipped[i],clipped[i+1]}) emit(out,p.x,p.y,p.z,color);
        }
    }
    private static int shade(int rgb, float light) {
        int r = Math.min(255,Math.round((rgb>>16&255)*light));
        int g = Math.min(255,Math.round((rgb>>8&255)*light));
        int b = Math.min(255,Math.round((rgb&255)*light));
        return r<<16 | g<<8 | b;
    }
    private static void emit(ByteBuffer out, float x, float y, float z, int rgb) {
        out.putFloat(x).putFloat(y).putFloat(z).put((byte)(rgb>>16)).put((byte)(rgb>>8)).put((byte)rgb).put((byte)255);
    }
    private static ByteBuffer buffer(int bytes) { return ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder()); }
    private record Point(float x,float y,float z,float depth) {}
}
