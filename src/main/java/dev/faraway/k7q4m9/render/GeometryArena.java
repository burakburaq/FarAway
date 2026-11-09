package dev.faraway.k7q4m9.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import dev.faraway.k7q4m9.streaming.ByteBudget;
import dev.faraway.k7q4m9.mesh.TileMesh;
import dev.faraway.k7q4m9.terrain.TileKey;
import java.nio.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/** Shared geometry pages. Slots become reusable only after the caller's retirement fence. */
final class GeometryArena {
    record Key(long x,long z,int spacing,boolean water,boolean packed) {}
    final Map<Key,ArrayList<Page>> pages=new HashMap<>();
    final ByteBudget budget;
    private final ArrayDeque<Runnable> retiredCommands=new ArrayDeque<>();
    void beginFrame() {
        flushRetiredCommands();
        for(var list:pages.values())for(var page:list)if(page.commandsUsed){page.commands.rotate();page.commandsUsed=false;page.commandAt=0;}
    }
    void flushRetiredCommands(){while(!retiredCommands.isEmpty())RenderSystem.queueFencedTask(retiredCommands.removeFirst());}
    GeometryArena(ByteBudget budget) { this.budget=budget; }
    final class Slot implements AutoCloseable {
        final Page page; final int vertex,index,count,vertices;
        int vertexRoom,indexRoom;
        boolean closed; int hiZId=-1; long selectedTriangles;
        float minY=Float.POSITIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
        java.util.List<FaceSelection.Run> runs=java.util.List.of();
        Slot(Page p,int v,int i,int c,int vs) { page=p;vertex=v;index=i;count=c;vertices=vs;vertexRoom=vs;indexRoom=p.key.water()||p.key.packed()?0:c; }
        public void close() {
            if(closed) return; closed=true;
            if(--page.references==0) { var list=pages.get(page.key);list.remove(page);if(list.isEmpty())pages.remove(page.key);page.vertex.close();if(page.index!=null)page.index.close();page.closeCommands();page.lease.close(); } else page.free.add(this);
        }
    }
    final class Page {
        final Key key; final GpuBuffer vertex,index; final ByteBudget.Reservation lease;
        final int vertexCapacity,indexCapacity;
        net.minecraft.client.renderer.MappableRingBuffer commands;
        ByteBudget.Reservation commandLease; int commandCapacity,commandAt; boolean commandsUsed;
        void closeCommands(){if(commands!=null){commands.close();commands=null;commandLease.close();}}
        int usedVertices,usedIndices,references,preparedDraws;
        com.mojang.renderpearl.api.buffers.GpuBufferSlice indirect;
        final ArrayList<Slot> visible=new ArrayList<>(),free=new ArrayList<>();
        IntBuffer ids=BufferUtils.createIntBuffer(16);
        IntBuffer first=BufferUtils.createIntBuffer(16),counts=BufferUtils.createIntBuffer(16),bases=BufferUtils.createIntBuffer(16);
        PointerBuffer offsets=BufferUtils.createPointerBuffer(16);
        Page(Key key,int v,int i) {
            this.key=key;vertexCapacity=capacity(Math.max(4096,v));indexCapacity=key.packed()?0:capacity(Math.max(4096,i));
            lease=budget.tryReserve((long)vertexCapacity*(key.packed()?40:16)+(long)indexCapacity*4);
            if(lease==null) throw new PlaneAtlas.BudgetUnavailable();
            GpuBuffer vb=null,ib=null;
            try { vb=RenderSystem.getDevice().createBuffer(()->"FarAway shared vertices",GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_COPY_DST,(long)vertexCapacity*(key.packed()?40:16));if(!key.packed())ib=RenderSystem.getDevice().createBuffer(()->"FarAway shared indices",GpuBuffer.USAGE_INDEX|GpuBuffer.USAGE_COPY_DST,(long)indexCapacity*4); }
            catch(RuntimeException e) { if(vb!=null)vb.close();if(ib!=null)ib.close();lease.close();throw e; }
            vertex=vb;index=ib;
        }
        int prepare(double cameraX,double cameraY,double cameraZ) {
            boolean selectFaces=dev.faraway.k7q4m9.client.LodSettings.get().gpuBackfaceCulling();
            int max=0;for(var slot:visible)max+=slot.runs.size();if(max==0)return 0;
            if(first.capacity()<max) {int c=capacity(max);ids=BufferUtils.createIntBuffer(c);first=BufferUtils.createIntBuffer(c);counts=BufferUtils.createIntBuffer(c);bases=BufferUtils.createIntBuffer(c);offsets=BufferUtils.createPointerBuffer(c);}
            first.clear();counts.clear();bases.clear();offsets.clear();ids.clear();
            boolean indexed=!key.water()&&!key.packed();int n=0;
            for(var slot:visible) {
                int start=-1,count=0;slot.selectedTriangles=0;
                for(var run:slot.runs) {
                    if(!selectFaces || run.visible(cameraX-key.x(),cameraY,cameraZ-key.z())) {
                        slot.selectedTriangles+=key.packed()?run.count()*2L:run.count()/3L;
                        if(start<0)start=run.first();count+=run.count();
                    } else if(count>0) {first.put(slot.vertex+start);counts.put(count);bases.put(slot.vertex);offsets.put((long)(slot.index+start)*4);ids.put(slot.hiZId);n++;start=-1;count=0;}
                }
                if(count>0) {first.put(slot.vertex+start);counts.put(count);bases.put(slot.vertex);offsets.put((long)(slot.index+start)*4);ids.put(slot.hiZId);n++;}
            }
            if(n==0)return 0;
            first.flip();counts.flip();bases.flip();offsets.flip();ids.flip();
            return n;
        }
        void prepareHiZ(double x,double y,double z,HiZ hiZ) {
            preparedDraws=prepare(x,y,z);
            indirect=preparedDraws==0?null:hiZ.queue(first,counts,bases,offsets,ids,!key.water()&&!key.packed(),preparedDraws,key.packed());
        }
        int draw(RenderPass pass,double cameraX,double cameraY,double cameraZ,HiZ hiZ) {
            int n=hiZ.active()?preparedDraws:prepare(cameraX,cameraY,cameraZ);if(n==0)return 0;
            boolean indexed=!key.water()&&!key.packed();
            var command=hiZ.paintDrawing?hiZ.painted(indirect):indirect;
            if(hiZ.paintDrawing&&command==null)return 0;
            for(int i=0;hiZ.counters.collecting()&&i<n;i++)hiZ.counters.add(ids.get(i),key.packed()?counts.get(i)*2:counts.get(i)/3,hiZ.active()&&indirect!=null);
            pass.setVertexBuffer(0,vertex.slice());if(indexed)pass.setIndexBuffer(index,IndexType.INT);
            if(!dev.faraway.k7q4m9.client.LodSettings.get().drawCalls())return 0;
            if(hiZ.active()&&command!=null) {if(indexed)pass.drawIndexedIndirect(command,n);else pass.drawIndirect(command,n);return 1;}
            if(key.packed())return drawQuads(pass,n);
            if(RenderSystem.getDevice().getDeviceInfo().features().multiDrawDirectSeparate()) {
                if(indexed)pass.multiDrawIndexed(offsets,counts,bases,n);else pass.multiDraw(first,counts,n);return 1;
            }
            for(int i=0;i<n;i++) {if(indexed)pass.drawIndexed(counts.get(i),1,(int)(offsets.get(i)/4),bases.get(i),0);else pass.draw(counts.get(i),1,first.get(i),0);}
            return n;
        }
        private int drawQuads(RenderPass pass,int n) {
            // One API draw per page; the indirect commands retain face-plane selection.
            int needed=capacity(commandAt/16+n)*16;
            if(commands==null || needed>commandCapacity) {
                var lease=budget.tryReserve((long)needed*3);
                if(lease!=null) {
                    net.minecraft.client.renderer.MappableRingBuffer replacement;
                    try {replacement=new net.minecraft.client.renderer.MappableRingBuffer(()->"FarAway quad commands",GpuBuffer.USAGE_INDIRECT_PARAMETERS|GpuBuffer.USAGE_MAP_WRITE,needed);}
                    catch(RuntimeException e){lease.close();throw e;}
                    var old=commands;var oldLease=commandLease;
                    if(old!=null)retiredCommands.addLast(()->{try{old.close();}finally{oldLease.close();}});
                    commands=replacement;commandLease=lease;commandCapacity=needed;commandAt=0;commandsUsed=false;
                }
            }
            if(commands==null || commandCapacity<commandAt+n*16) {
                for(int i=0;i<n;i++)pass.draw(6,counts.get(i),0,first.get(i));return n;
            }
            var buffer=commands.currentBuffer();
            try(var mapped=buffer.slice(commandAt,n*16L).map(false,true)) {
                var data=mapped.data().order(ByteOrder.nativeOrder());
                for(int i=0;i<n;i++)data.putInt(6).putInt(counts.get(i)).putInt(0).putInt(first.get(i));
            }
            pass.drawIndirect(buffer.slice(commandAt,n*16L),n);commandAt+=n*16;commandsUsed=true;return 1;
        }

    }

    Slot upload(TileMesh mesh,boolean water) {
        var key=mesh.key();long span=(long)key.blocks()*4;
        var region=new Key(Math.floorDiv(key.originX(),span)*span,Math.floorDiv(key.originZ(),span)*span,key.spacing(),water,false);
        int vs=water?mesh.waterVertexCount():mesh.vertexCount();int count=water?vs:mesh.indexCount()>0?mesh.indexCount():vs;
        var list=pages.computeIfAbsent(region,k->new ArrayList<>());
        Page page=null;Slot recycled=null;
        for(var candidate:list) {
            for(var hole:candidate.free)if(hole.vertexRoom>=vs && hole.indexRoom>=(water?0:count)){page=candidate;recycled=hole;break;}
            if(page!=null)break;
            if(candidate.vertexCapacity-candidate.usedVertices>=vs && candidate.indexCapacity-candidate.usedIndices>=(water?0:count)){page=candidate;break;}
        }
        if(page==null) {try {page=new Page(region,vs,water?0:count);list.add(page);}catch(RuntimeException e){if(list.isEmpty())pages.remove(region);throw e;} }
        var slot=new Slot(page,recycled==null?page.usedVertices:recycled.vertex,recycled==null?page.usedIndices:recycled.index,count,vs);page.references++;
        if(recycled!=null){page.free.remove(recycled);slot.vertexRoom=recycled.vertexRoom;slot.indexRoom=recycled.indexRoom;}
        if(recycled==null){page.usedVertices+=vs;page.usedIndices+=water?0:count;}
        var source=(water?mesh.waterVertices():mesh.vertices()).order(ByteOrder.nativeOrder());
        var data=MemoryUtil.memAlloc(vs*16).order(ByteOrder.nativeOrder());
        var ids=MemoryUtil.memAlloc(water?0:count*4).order(ByteOrder.nativeOrder());
        try {
            for(int v=0;v<vs;v++) {
                float x,y,z;int color;
                if(!water && mesh.vertexStride()==8) {int packed=source.getInt();x=(packed&63)*key.spacing();z=((packed>>>6)&63)*key.spacing();y=(short)((packed>>>12)&65535)/16f;color=source.getInt();}
                else {x=source.getFloat();y=source.getFloat();z=source.getFloat();color=source.getInt();}
                slot.minY=Math.min(slot.minY,y);slot.maxY=Math.max(slot.maxY,y);
                data.putFloat((float)((double)x+key.originX()-region.x())).putFloat(y).putFloat((float)((double)z+key.originZ()-region.z())).putInt(color);
            }
            data.flip();
            int[] raw=new int[count];var indices=mesh.indices().order(ByteOrder.nativeOrder());
            for(int i=0;i<count;i++)raw[i]=water || mesh.indexCount()==0?i:mesh.indexStride()==2?Short.toUnsignedInt(indices.getShort()):indices.getInt();
            var packed=FaceSelection.pack(data,raw);slot.runs=packed.runs();
            if(water) {
                var ordered=MemoryUtil.memAlloc(vs*16).order(ByteOrder.nativeOrder());
                try {for(int v:packed.indices())for(int k=0;k<16;k+=4)ordered.putInt(data.getInt(v*16+k));ordered.flip();data.clear();data.put(ordered).flip();}
                finally {MemoryUtil.memFree(ordered);}
            } else {for(int v:packed.indices())ids.putInt(v);ids.flip();}
            var encoder=RenderSystem.getDevice().createCommandEncoder();encoder.writeToBuffer(page.vertex.slice((long)slot.vertex*16,(long)vs*16),data);
            if(!water)encoder.writeToBuffer(page.index.slice((long)slot.index*4,(long)count*4),ids);encoder.submit();
            return slot;
        } catch(RuntimeException e) {slot.close();throw e;}finally{MemoryUtil.memFree(data);MemoryUtil.memFree(ids);}
    }

    private record QuadPlane(int axis,int sign,float d) {}
    Slot uploadQuads(TileMesh mesh) {
        var key=mesh.key();long span=(long)key.blocks()*4;
        var region=new Key(Math.floorDiv(key.originX(),span)*span,Math.floorDiv(key.originZ(),span)*span,key.spacing(),false,true);
        int count=mesh.quadCount();var list=pages.computeIfAbsent(region,k->new ArrayList<>());
        Page page=null;Slot recycled=null;
        for(var candidate:list) {
            for(var hole:candidate.free)if(hole.vertexRoom>=count){page=candidate;recycled=hole;break;}
            if(page!=null)break;
            if(candidate.vertexCapacity-candidate.usedVertices>=count){page=candidate;break;}
        }
        if(page==null){try{page=new Page(region,count,0);list.add(page);}catch(RuntimeException e){if(list.isEmpty())pages.remove(region);throw e;}}
        var slot=new Slot(page,recycled==null?page.usedVertices:recycled.vertex,0,count,count);page.references++;
        if(recycled!=null){page.free.remove(recycled);slot.vertexRoom=recycled.vertexRoom;}else page.usedVertices+=count;
        var source=mesh.quads().order(ByteOrder.nativeOrder());var groups=new HashMap<QuadPlane,it.unimi.dsi.fastutil.ints.IntArrayList>();
        var data=MemoryUtil.memAlloc(count*40).order(ByteOrder.nativeOrder());
        try {
            for(int i=0;i<count;i++) {
                int at=i*40,axes=source.getInt(at+20),u=axes&3,v=(axes>>>2)&3,normal=3-u-v;
                float du=source.getFloat(at+12),dv=source.getFloat(at+16);
                int sign=((u+1)%3==v?1:-1)*(du*dv>0?1:-1);
                float d=source.getFloat(at+normal*4);
                if(normal==0)d=(float)((double)d+key.originX()-region.x());
                if(normal==2)d=(float)((double)d+key.originZ()-region.z());
                d*=sign;if(d==0)d=0;
                groups.computeIfAbsent(new QuadPlane(normal,sign,d),ignored->new it.unimi.dsi.fastutil.ints.IntArrayList()).add(i);
            }
            var planes=new ArrayList<>(groups.keySet());planes.sort(Comparator.comparingInt(QuadPlane::axis).thenComparingInt(QuadPlane::sign).thenComparingDouble(QuadPlane::d));
            var runs=new ArrayList<FaceSelection.Run>();int first=0;
            for(var plane:planes) {
                var group=groups.get(plane);runs.add(new FaceSelection.Run(first,group.size(),plane.axis()==0?plane.sign():0,plane.axis()==1?plane.sign():0,plane.axis()==2?plane.sign():0,plane.d()));
                for(int i:group) {
                    int at=i*40;float y=source.getFloat(at+4),du=source.getFloat(at+12),dv=source.getFloat(at+16);int axes=source.getInt(at+20);
                    float dy=(axes&3)==1?du:((axes>>>2)&3)==1?dv:0;
                    slot.minY=Math.min(slot.minY,Math.min(y,y+dy));slot.maxY=Math.max(slot.maxY,Math.max(y,y+dy));
                    data.putFloat((float)((double)source.getFloat(at)+key.originX()-region.x())).putFloat(y).putFloat((float)((double)source.getFloat(at+8)+key.originZ()-region.z()));
                    for(int k=12;k<40;k+=4)data.putInt(source.getInt(at+k));
                }
                first+=group.size();
            }
            slot.runs=List.copyOf(runs);data.flip();var encoder=RenderSystem.getDevice().createCommandEncoder();encoder.writeToBuffer(page.vertex.slice(slot.vertex*40L,count*40L),data);encoder.submit();return slot;
        }catch(RuntimeException e){slot.close();throw e;}finally{MemoryUtil.memFree(data);}
    }
    private static int capacity(int n){int c=1;while(c<n)c=Math.multiplyExact(c,2);return c;}
}
