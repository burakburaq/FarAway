package dev.faraway.k7q4m9.render;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/** Current drawable vanilla surface columns, never a configured render-distance hole. */
public final class VanillaCoverage {
    public static final int WIDTH=128, WORDS=WIDTH*WIDTH/32, BYTES=16+WORDS*8;
    private final LongOpenHashSet columns=new LongOpenHashSet();
    private final LinkedHashMap<Long,Surface> surfaces=new LinkedHashMap<>(256,.75f,true);
    private final int[] bits=new int[WORDS],seamBits=new int[WORDS];
    private final ByteBuffer upload=ByteBuffer.allocate(BYTES).order(ByteOrder.nativeOrder());
    private int originX,originZ,ready;
    private final BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
    private final SodiumCoverage sodiumCoverage=new SodiumCoverage();
    private final LongOpenHashSet drawnSections=new LongOpenHashSet();

    public void reset() { surfaces.clear(); columns.clear(); Arrays.fill(bits,0); ready=0; }
    public int readyColumns() { return ready; }
    private boolean ready(int x,int z) {
        int mx=x-originX,mz=z-originZ;
        if(mx<0 || mz<0 || mx>=WIDTH || mz>=WIDTH) return false;
        int index=mz*WIDTH+mx;
        return (bits[index>>>5]&(1<<(index&31)))!=0;
    }

    record Edge(int x,int z,Direction face,LevelChunk chunk,long revision) {
        Edge(int x,int z,Direction face,LevelChunk chunk) {
            this(x,z,face,chunk,((ChunkBorderRevision)chunk).faraway$borderRevision());
        }
        int dx() { return face.getStepX(); }
        int dz() { return face.getStepZ(); }
        int insideX(int along) { return x*16+(dx()<0?0:dx()>0?15:along); }
        int insideZ(int along) { return z*16+(dz()<0?0:dz()>0?15:along); }
        int height(int along) {
            return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,insideX(along)&15,insideZ(along)&15)+1;
        }
        BlockState block(int along,int y,BlockPos.MutableBlockPos cursor) {
            return chunk.getBlockState(cursor.set(insideX(along),y,insideZ(along)));
        }
    }

    java.util.List<Edge> edges() {
        var result=new java.util.ArrayList<Edge>();
        for(long key:columns) {
            int x=ChunkPos.getX(key),z=ChunkPos.getZ(key);
            if(!ready(x,z)) continue;
            var surface=surfaces.get(key);
            if(surface==null) continue;
            if(!ready(x-1,z)) result.add(new Edge(x,z,Direction.WEST,surface.chunk()));
            if(!ready(x+1,z)) result.add(new Edge(x,z,Direction.EAST,surface.chunk()));
            if(!ready(x,z-1)) result.add(new Edge(x,z,Direction.NORTH,surface.chunk()));
            if(!ready(x,z+1)) result.add(new Edge(x,z,Direction.SOUTH,surface.chunk()));
        }
        return result;
    }
    boolean covers(net.minecraft.world.phys.AABB bounds) {
        int x0=Math.floorDiv((int)Math.floor(bounds.minX),16)-originX,z0=Math.floorDiv((int)Math.floor(bounds.minZ),16)-originZ;
        int x1=Math.floorDiv((int)Math.ceil(bounds.maxX)-1,16)-originX,z1=Math.floorDiv((int)Math.ceil(bounds.maxZ)-1,16)-originZ;
        if(x0<0||z0<0||x1>=WIDTH||z1>=WIDTH)return false;
        for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++){int i=z*WIDTH+x;if((bits[i>>>5]&(1<<(i&31)))==0)return false;}
        return true;
    }
    public void update(Minecraft client) {
        Arrays.fill(bits,0); columns.clear(); ready=0;
        var camera=dev.faraway.k7q4m9.client.Freecam.planPosition(client.gameRenderer.mainCamera().blockPosition());
        originX=Math.floorDiv(camera.getX(),16)-WIDTH/2;
        originZ=Math.floorDiv(camera.getZ(),16)-WIDTH/2;
        sodiumCoverage.collect(drawnSections);
        if(client.level!=null) {
            for(long section:drawnSections)columns.add(ChunkPos.fromSectionNode(section));
            for(long key:columns) {
                int x=ChunkPos.getX(key),z=ChunkPos.getZ(key),mx=x-originX,mz=z-originZ;
                if(mx<0 || mz<0 || mx>=WIDTH || mz>=WIDTH) continue;
                var chunk=client.level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false);
                if(chunk==null || !chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)) continue;
                long hash=Arrays.hashCode(chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES).getRawData());
                // Sodium owns the section meshes. Use the client chunk mutation directly,
                // including edits below the top block and packets replacing an existing chunk.
                hash=31*hash+((ChunkBorderRevision)chunk).faraway$borderRevision();
                var surface=surfaces.get(key);
                if(surface==null || surface.chunk()!=chunk || surface.hash()!=hash) {
                    surface=surface(chunk,hash); surfaces.put(key,surface);
                }
                boolean drawable=false;
                // Only submitted ground sections own terrain coverage. Foliage-only
                // sections must not punch a hole in LOD after terrain is culled.
                for(int sy=surface.minSection();sy<=surface.groundMaxSection();sy++) {
                    if(drawnSections.contains(SectionPos.asLong(x,sy,z))) {drawable=true;break;}
                }
                if(drawable) { int index=mz*WIDTH+mx; bits[index>>>5]|=1<<(index&31); ready++; }
            }
        }
        while(surfaces.size()>4096) surfaces.remove(surfaces.keySet().iterator().next());
    }

    ByteBuffer data(LongOpenHashSet patchedChunks) {
        Arrays.fill(seamBits,0);
        for(long key:patchedChunks) {
            int x=ChunkPos.getX(key)-originX,z=ChunkPos.getZ(key)-originZ;
            if(x<0 || z<0 || x>=WIDTH || z>=WIDTH) continue;
            int index=z*WIDTH+x;
            seamBits[index>>>5]|=1<<(index&31);
        }
        upload.clear();
        upload.putInt(originX).putInt(originZ).putInt(WIDTH).putInt(0);
        for(int word:bits) upload.putInt(word);
        for(int word:seamBits) upload.putInt(word);
        return upload.flip();
    }
    private Surface surface(LevelChunk chunk,long hash) {
        int min=chunk.getMaxY(),groundMax=chunk.getMinY();
        int baseX=chunk.getPos().getMinBlockX(),baseZ=chunk.getPos().getMinBlockZ();
        for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
            int floor=chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
            // Client heightmaps include water. Find its loaded terrain floor without
            // requesting any world data or constructing a new heightmap/chunk.
            while(floor>chunk.getMinY() && !chunk.getBlockState(cursor.set(baseX+x,floor,baseZ+z)).getFluidState().isEmpty()) floor--;
            min=Math.min(min,Math.max(chunk.getMinY(),floor));
            groundMax=Math.max(groundMax,floor);
        }
        return new Surface(chunk,hash,Math.floorDiv(min,16),Math.floorDiv(groundMax,16));
    }
    private record Surface(LevelChunk chunk,long hash,int minSection,int groundMaxSection) {}
}

