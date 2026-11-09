package dev.faraway.k7q4m9.mesh;

import java.nio.ByteBuffer;
import dev.faraway.k7q4m9.terrain.TileKey;

/** Owned byte arrays are never exposed for mutation. GPU format: float xyz + RGBA8. */
public final class TileMesh {
    private final TileKey key;
    private final byte[] vertices, indices, waterVertices;
    private final int minY, maxY;
    private final int vertexStride,indexStride;
    private dev.faraway.k7q4m9.terrain.FarPlaneTile farPlane;
    private byte[] quads=new byte[0];
    private VoxelSurface voxelSurface;
    private MeshPerimeter perimeter;
    public MeshPerimeter perimeter() { return perimeter; }
    TileMesh withPerimeter(MeshPerimeter value) { perimeter=value;return this; }
    public VoxelSurface voxelSurface() { return voxelSurface; }
    TileMesh withVoxelSurface(VoxelSurface surface) { voxelSurface=surface;return this; }
    public long heapBytes() { return bytes()+(voxelSurface==null?0:voxelSurface.bytes())+(perimeter==null?0:perimeter.bytes()); }
    public ByteBuffer quads() { return ByteBuffer.wrap(quads).asReadOnlyBuffer(); }
    public int quadCount() { return quads.length/PackedQuads.STRIDE; }
    private TileMesh withQuads(byte[] data) { quads=data;return this; }
    public TileMesh packQuads(java.util.function.BooleanSupplier cancelled) {
        if(farPlane!=null || quads.length>0 || key.spacing()==1 || vertexStride!=16 || indices.length!=0) return this;
        var packed=PackedQuads.pack(vertices,cancelled);
        return new TileMesh(key,packed.fallback(),indices,waterVertices,minY,maxY).withQuads(packed.quads()).withVoxelSurface(voxelSurface).withPerimeter(perimeter);
    }
    public static TileMesh farPlane(dev.faraway.k7q4m9.terrain.FarPlaneTile plane) {
        var mesh=new TileMesh(plane.key(),new byte[0],new byte[0],new byte[0],plane.seaLevel(),plane.seaLevel());
        mesh.farPlane=plane; return mesh;
    }
    public dev.faraway.k7q4m9.terrain.FarPlaneTile farPlane() { return farPlane; }
    TileMesh(TileKey key, byte[] vertices, byte[] indices, byte[] waterVertices, int minY, int maxY) {
        this(key,vertices,indices,waterVertices,minY,maxY,16,2);
    }
    private TileMesh(TileKey key, byte[] vertices, byte[] indices, byte[] waterVertices, int minY, int maxY,int vertexStride,int indexStride) {
        this.key = key; this.vertices = vertices; this.indices = indices; this.waterVertices = waterVertices;
        this.minY = minY; this.maxY = maxY; this.vertexStride=vertexStride; this.indexStride=indexStride;
    }
    public TileKey key() { return key; }
    public ByteBuffer vertices() { return ByteBuffer.wrap(vertices).asReadOnlyBuffer(); }
    public ByteBuffer indices() { return ByteBuffer.wrap(indices).asReadOnlyBuffer(); }
    public ByteBuffer waterVertices() { return ByteBuffer.wrap(waterVertices).asReadOnlyBuffer(); }
    public int indexCount() { return indices.length / indexStride; }
    public int indexStride() { return indexStride; }
    public int vertexStride() { return vertexStride; }
    public int vertexCount() { return vertices.length/vertexStride; }
    public int waterVertexCount() { return waterVertices.length / 16; }
    public long triangleCount() { return farPlane!=null?2:(indices.length==0?vertexCount():indexCount())/3L+waterVertexCount()/3L+quadCount()*2L; }
    public long bytes() { return (long)vertices.length + indices.length + waterVertices.length+quads.length+(farPlane==null?0:farPlane.rgba().length); }
    public int minY() { return minY; }
    public int maxY() { return maxY; }
    private record Vertex(int x,int y,int z,int color) {}
    /** Pack exact grid vertices and share identical vertices; never quantizes geometry. */
    public TileMesh optimize(boolean packGrid,java.util.function.BooleanSupplier cancelled) {
        if(farPlane!=null) return this;
        if(vertexStride!=16 || indexStride!=2) throw new IllegalStateException("Mesh already optimized");
        var source=ByteBuffer.wrap(vertices).order(java.nio.ByteOrder.nativeOrder());
        boolean packed=packGrid && key.spacing()>1;
        for(int i=0;packed && i<vertices.length;i+=16) {
            float x=source.getFloat(i)/key.spacing(),y=source.getFloat(i+4)*16,z=source.getFloat(i+8)/key.spacing();
            packed=Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z) && x>=0 && x<=32 && z>=0 && z<=32
                && x==Math.rint(x) && z==Math.rint(z) && y>=Short.MIN_VALUE && y<=Short.MAX_VALUE && y==Math.rint(y);
        }
        var unique=new java.util.HashMap<Vertex,Integer>();
        var data=new java.io.ByteArrayOutputStream();
        int count=indices.length==0?vertices.length/16:indices.length/2;
        int[] refs=new int[count]; var sourceIndices=ByteBuffer.wrap(indices).order(java.nio.ByteOrder.nativeOrder());
        var temp=ByteBuffer.allocate(16).order(java.nio.ByteOrder.nativeOrder());
        for(int i=0;i<count;i++) {
            if((i&4095)==0 && cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            int off=(indices.length==0?i:Short.toUnsignedInt(sourceIndices.getShort(i*2)))*16;
            var v=new Vertex(source.getInt(off),source.getInt(off+4),source.getInt(off+8),source.getInt(off+12));
            Integer index=unique.get(v);
            if(index==null) {
                index=unique.size(); unique.put(v,index); temp.clear();
                if(packed) {
                    int x=(int)(Float.intBitsToFloat(v.x())/key.spacing()),z=(int)(Float.intBitsToFloat(v.z())/key.spacing());
                    int y=(int)(Float.intBitsToFloat(v.y())*16);
                    temp.putInt(x|(z<<6)|((y&65535)<<12)).putInt(v.color());
                    data.write(temp.array(),0,8);
                } else { temp.putInt(v.x()).putInt(v.y()).putInt(v.z()).putInt(v.color()); data.write(temp.array(),0,16); }
            }
            refs[i]=index;
        }
        int indexSize=unique.size()<=65536?2:4;
        var outputIndices=ByteBuffer.allocate(count*indexSize).order(java.nio.ByteOrder.nativeOrder());
        for(int index:refs) if(indexSize==2) outputIndices.putShort((short)index); else outputIndices.putInt(index);
        byte[] out=data.toByteArray();
        // Indexing can cost more for meshes with no reusable vertices.
        if(out.length+outputIndices.capacity()>count*(packed?8:16)) {
            var unindexed=ByteBuffer.allocate(count*(packed?8:16));
            for(int index:refs) unindexed.put(out,index*(packed?8:16),packed?8:16);
            return new TileMesh(key,unindexed.array(),new byte[0],waterVertices,minY,maxY,packed?8:16,2).withQuads(quads).withVoxelSurface(voxelSurface).withPerimeter(perimeter);
        }
        return new TileMesh(key,out,outputIndices.array(),waterVertices,minY,maxY,packed?8:16,indexSize).withQuads(quads).withVoxelSurface(voxelSurface).withPerimeter(perimeter);
    }
    /** Publish terrain and required structures as one atomic tile payload. */
    public TileMesh append(TileMesh extra) {
        if(!key.equals(extra.key)) throw new IllegalArgumentException("Different tile origins");
        if(extra.bytes()==0) return this;
        byte[] expanded;
        if(indices.length==0) expanded=vertices;
        else {
            expanded=new byte[indexCount()*16];
            var in=ByteBuffer.wrap(indices).order(java.nio.ByteOrder.nativeOrder());
            for(int i=0;i<indexCount();i++) System.arraycopy(vertices,Short.toUnsignedInt(in.getShort())*16,expanded,i*16,16);
        }
        byte[] joined=java.util.Arrays.copyOf(expanded,expanded.length+extra.vertices.length);
        System.arraycopy(extra.vertices,0,joined,expanded.length,extra.vertices.length);
        return new TileMesh(key,joined,new byte[0],waterVertices,Math.min(minY,extra.minY),Math.max(maxY,extra.maxY)).withVoxelSurface(voxelSurface).withPerimeter(perimeter);
    }
}
