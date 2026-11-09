package dev.faraway.k7q4m9.mesh;

import dev.faraway.k7q4m9.terrain.VoxelTile;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BooleanSupplier;

public final class VoxelMeshBuilder {
    private static final int[][] DIR={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
    private static final int[] TRIANGLES={0,1,2,2,1,3};
    private static final int[][][] FACE={{{0,0,1},{0,1,1},{0,0,0},{0,1,0}},{{1,0,0},{1,1,0},{1,0,1},{1,1,1}},
        {{0,0,0},{1,0,0},{0,0,1},{1,0,1}},{{0,1,1},{1,1,1},{0,1,0},{1,1,0}},
        {{0,0,0},{0,1,0},{1,0,0},{1,1,0}},{{1,0,1},{1,1,1},{0,0,1},{0,1,1}}};
    public TileMesh build(VoxelTile tile,BooleanSupplier cancelled) {
        var terrain=new ByteArrayOutputStream(); var water=new ByteArrayOutputStream();
        var vertex=ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        int spacing=tile.key().spacing(),cells=tile.cells();
        var surfaceProfile=new VoxelSurface(tile);
        var perimeter=new MeshPerimeter(cells);
        for(int i=0;i<cells;i++) {
            int last=cells-1,opposite=last-i;
            int north=surfaceY(tile,i,0),east=surfaceY(tile,last,i);
            int south=surfaceY(tile,opposite,last),west=surfaceY(tile,0,opposite);
            perimeter.set(MeshPerimeter.NORTH,i,north,north);
            perimeter.set(MeshPerimeter.EAST,i,east,east);
            perimeter.set(MeshPerimeter.SOUTH,i,south,south);
            perimeter.set(MeshPerimeter.WEST,i,west,west);
        }
        for(int f=0;f<6;f++) {
            int planes=f<2 || f>=4?cells:tile.layers();
            int width=cells,height=f<2 || f>=4?tile.layers():cells;
            long[] mask=new long[width*height];
            for(int plane=0;plane<planes;plane++) {
                if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                for(int v=0;v<height;v++) for(int u=0;u<width;u++) {
                    int x=f<2?plane:u,y=f<2 || f>=4?v:plane,z=f<2?u:f>=4?plane:v;
                    int kind=tile.kind(x,y,z); long key=0;
                    if(kind==1 && !tile.wet(x,z)) {
                        var d=DIR[f]; int neighbor=tile.kind(x+d[0],y+d[1],z+d[2]);
                        int surfaceTop=tile.terrainSurface(x,z);
                        int neighborTop=d[1]==0?tile.terrainSurface(x+d[0],z+d[2]):surfaceTop;
                        int clipBottom=d[1]==0?neighborTop:tile.bottom();
                        if(d[1]==0 && tile.wet(x+d[0],z+d[2])) clipBottom=Math.max(clipBottom,tile.waterSurface(x+d[0],z+d[2]));
                        boolean heightStep=d[1]==0 && neighbor==kind && surfaceTop>neighborTop
                            && tile.bottom()+(y+1)*tile.stepY()>neighborTop && tile.bottom()+y*tile.stepY()<surfaceTop;
                        if(f!=2 && (heightStep || (neighbor!=kind && neighbor!=1)) && Math.min(surfaceTop,tile.bottom()+(y+1)*tile.stepY())>clipBottom) {
                            int top=surfaceTop;
                            if(top<=tile.bottom()+y*tile.stepY()) top=tile.bottom()+(y+1)*tile.stepY();
                            // Include the water clip floor in the greedy key; unequal shoreline
                            // clipping must never merge into a submerged rectangle.
                            key=((long)(tile.material(x,z)+1)<<56)|((long)(clipBottom+32768)<<40)|((long)(top+32768)<<24);
                        }
                    }
                    mask[v*width+u]=key;
                }
                for(int v=0;v<height;v++) for(int u=0;u<width;) {
                    long key=mask[v*width+u]; if(key==0) {u++;continue;}
                    int w=1,h=1;
                    while(u+w<width && mask[v*width+u+w]==key) w++;
                    outer:while(v+h<height) { for(int j=0;j<w;j++) if(mask[(v+h)*width+u+j]!=key) break outer; h++; }
                    for(int j=0;j<h;j++) java.util.Arrays.fill(mask,(v+j)*width+u,(v+j)*width+u+w,0);
                    int x=f<2?plane:u,y=f<2 || f>=4?v:plane,z=f<2?u:f>=4?plane:v;
                    int dx=f<2?1:w,dy=f<2 || f>=4?h:1,dz=f<2?w:f>=4?1:h;
                    int material=(int)(key>>>56)-1,color=tile.color(x,y,z),waterTop=(int)((key>>>24)&65535)-32768,clipBottom=(int)((key>>>40)&65535)-32768;
                    float shade=f==3?1:f==2?.5f:f<2?.72f:.86f;
                    if(material!=0) color=0xffffff;
                    int r=Math.round((color>>16&255)*shade),g=Math.round((color>>8&255)*shade),b=Math.round((color&255)*shade);
                    int lowerY=Math.max(clipBottom,Math.min(tile.bottom()+y*tile.stepY(),waterTop));
                    switch(f) {
                        case 0 -> { if(x==0) perimeter.lower(MeshPerimeter.WEST,cells-z-w,w,lowerY); }
                        case 1 -> { if(x==cells-1) perimeter.lower(MeshPerimeter.EAST,z,w,lowerY); }
                        case 4 -> { if(z==0) perimeter.lower(MeshPerimeter.NORTH,x,w,lowerY); }
                        case 5 -> { if(z==cells-1) perimeter.lower(MeshPerimeter.SOUTH,cells-x-w,w,lowerY); }
                    }
                    var out=terrain;
                    for(int corner:TRIANGLES) {
                        var p=FACE[f][corner]; vertex.clear();
                        int vr=r,vg=g,vb=b;
                        if(material==0 && f==3 && tile.blendColors()) {
                            int vx=x+p[0]*dx,vz=z+p[2]*dz;
                            int blended=cornerColor(tile,vx,vz);
                            vr=(blended>>16)&255; vg=(blended>>8)&255; vb=blended&255;
                        }
                        float py=tile.bottom()+(y+p[1]*dy)*tile.stepY();
                        py=Math.max(clipBottom,Math.min(py,waterTop));
                        vertex.putFloat((x+p[0]*dx)*(float)spacing).putFloat(py).putFloat((z+p[2]*dz)*(float)spacing)
                            .put((byte)vr).put((byte)vg).put((byte)vb).put((byte)(material+1));
                        out.writeBytes(vertex.array());
                    }
                    u+=w;
                }
            }
        }
        // Surface water is independent of the vertical sampling lattice. A shallow sea
        // layer must survive even when no coarse voxel sample lands inside the water.
        // These caps replace grid-water top faces; identical heights meet exactly at LOD edges.
        long[] surface=new long[cells*cells];
        for(int z=0;z<cells;z++) for(int x=0;x<cells;x++) surface[z*cells+x]=waterKey(tile,x,z);
        for(int z=0;z<cells;z++) for(int x=0;x<cells;) {
            if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            long key=surface[z*cells+x]; if(key==0) { x++; continue; }
            int w=1,h=1;
            while(x+w<cells && surface[z*cells+x+w]==key) w++;
            outer:while(z+h<cells) { for(int j=0;j<w;j++) if(surface[(z+h)*cells+x+j]!=key) break outer; h++; }
            for(int j=0;j<h;j++) java.util.Arrays.fill(surface,(z+j)*cells+x,(z+j)*cells+x+w,0);
            int top=tile.waterSurface(x,z);
            surfaceQuad(water,vertex,tile,x,z,w,h,spacing,top);
            x+=w;
        }
        return new TileMesh(tile.key(),terrain.toByteArray(),new byte[0],water.toByteArray(),tile.oceanOnly()?tile.waterSurface(0,0):tile.bottom(),tile.oceanOnly()?tile.waterSurface(0,0):tile.bottom()+tile.layers()*tile.stepY()).withVoxelSurface(surfaceProfile).withPerimeter(perimeter);
    }
    private static int surfaceY(VoxelTile tile,int x,int z) {
        return tile.wet(x,z)?tile.waterSurface(x,z):tile.terrainSurface(x,z);
    }
    private static int cornerColor(VoxelTile tile,int x,int z) {
        int r=0,g=0,b=0,n=0;
        for(int dz=-1;dz<=0;dz++) for(int dx=-1;dx<=0;dx++) {
            int sx=x+dx,sz=z+dz;
            if(tile.material(sx,sz)!=0) continue;
            int color=tile.surfaceColor(sx,sz); r+=color>>16&255;g+=color>>8&255;b+=color&255;n++;
        }
        if(n==0) return tile.surfaceColor(Math.min(tile.cells(),x),Math.min(tile.cells(),z));
        return (r/n<<16)|(g/n<<8)|b/n;
    }
    private static long waterKey(VoxelTile tile,int x,int z) {
        return tile.wet(x,z)?(long)(tile.waterSurface(x,z)+32768)+1:0;
    }
    private static void surfaceQuad(ByteArrayOutputStream out,ByteBuffer vertex,VoxelTile tile,int x,int z,int width,int height,int spacing,int top) {
        for(int corner:TRIANGLES) {
            var p=FACE[3][corner]; vertex.clear();
            int vx=x+p[0]*width,vz=z+p[2]*height;
            int color=!tile.blendColors()?tile.waterColor(x,z):waterCornerColor(tile,vx,vz);
            vertex.putFloat(vx*(float)spacing).putFloat(top).putFloat(vz*(float)spacing)
                .put((byte)(color>>16)).put((byte)(color>>8)).put((byte)color).put((byte)255);
            out.writeBytes(vertex.array());
        }
    }
    private static int waterCornerColor(VoxelTile tile,int x,int z) {
        int r=0,g=0,b=0,n=0;
        for(int dz=-1;dz<=0;dz++) for(int dx=-1;dx<=0;dx++) {
            int sx=x+dx,sz=z+dz;
            if(!tile.wet(sx,sz)) continue;
            int c=tile.waterColor(sx,sz);r+=c>>16&255;g+=c>>8&255;b+=c&255;n++;
        }
        return n==0?0xffffff:(r/n<<16)|(g/n<<8)|b/n;
    }
}
