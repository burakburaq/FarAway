package dev.faraway.k7q4m9.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.backend.opengl.*;
import dev.faraway.k7q4m9.streaming.ByteBudget;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;
import java.util.*;
import static org.lwjgl.opengl.GL43C.*;

/** Current-frame reverse-Z culling. Visibility never crosses back to the CPU. */
final class HiZ implements AutoCloseable {
    private static final int COMMAND_BYTES=4*1024*1024, META_BYTES=1024*1024, INSTANCE_BYTES=2*1024*1024;
    private final ArrayList<Frame> frames=new ArrayList<>();
    private int pyramid,levels,width,height,paddedWidth,paddedHeight,sampler,reduce,test,commands,planes;
    private boolean unavailable;
    boolean paintEnabled,paintDrawing;
    private Frame frame;
    final HiZCounters counters=new HiZCounters();
    final HiZProfile profile=new HiZProfile();
    int testedTiles,drawCommands,planeCandidates;
    private ByteBuffer commandData,idData;
    private ByteBudget.Reservation textureMemory;
    private final Map<String,Integer> uniforms=new HashMap<>();
    private static final class Frame implements AutoCloseable {
        GpuBuffer bounds,visible,args,ids,instances,paint;ByteBudget.Reservation lease,paintLease;
        long fence;int tileCapacity,argAt,idAt,instanceAt;
        Frame(int n,ByteBudget budget) {
            tileCapacity=n;long bytes=36L*n+COMMAND_BYTES+META_BYTES+INSTANCE_BYTES;
            lease=budget.tryReserve(bytes);if(lease==null)throw new PlaneAtlas.BudgetUnavailable();
            try {bounds=buffer(n*32);visible=buffer(n*4);args=buffer(COMMAND_BYTES,GpuBuffer.USAGE_INDIRECT_PARAMETERS);ids=buffer(META_BYTES);instances=buffer(INSTANCE_BYTES,GpuBuffer.USAGE_UNIFORM);}
            catch(RuntimeException e){close();throw e;}
        }
        public void close(){if(fence!=0)glDeleteSync(fence);for(var b:new GpuBuffer[]{bounds,visible,args,ids,instances,paint})if(b!=null)b.close();if(lease!=null)lease.close();if(paintLease!=null)paintLease.close();}
    }
    private static GpuBuffer buffer(int bytes,int... extra){int usage=GpuBuffer.USAGE_COPY_DST;for(int u:extra)usage|=u;return RenderSystem.getDevice().createBuffer(()->"FarAway Hi-Z",usage,bytes);}
    boolean supported(){if(!RenderSystem.getDevice().getDeviceInfo().backendName().equals("OpenGL"))return false;var c=GL.getCapabilities();var f=RenderSystem.getDevice().getDeviceInfo().features();return !unavailable&&(c.OpenGL43||(c.GL_ARB_compute_shader&&c.GL_ARB_shader_storage_buffer_object&&c.GL_ARB_shader_image_load_store&&c.GL_ARB_shading_language_420pack&&c.GL_ARB_texture_storage))&&f.drawIndirect()&&f.multiDrawIndirect();}
    boolean active(){return frame!=null;}
    GpuBufferSlice painted(GpuBufferSlice normal){return frame==null||frame.paint==null||normal==null?null:frame.paint.slice(normal.offset(),normal.length());}
    boolean paintAvailable(){return active()&&paintEnabled&&frame.paint!=null;}
    GpuBuffer visibilityBuffer(){return frame.visible;}
    boolean begin(GlTexture depth,int w,int h,CameraRenderState camera,List<AABB> bounds,ByteBudget budget) {
        frame=null;testedTiles=drawCommands=planeCandidates=0;if(!supported()||bounds.isEmpty())return false;
        try(var state=new State()) {
            init();
            int n=1024;while(n<bounds.size())n*=2;
            Frame chosen=null;
            for(var f:frames){if(f.fence!=0){int status=glClientWaitSync(f.fence,0,0);if(status==GL_ALREADY_SIGNALED||status==GL_CONDITION_SATISFIED){glDeleteSync(f.fence);f.fence=0;}}if(f.fence==0&&f.tileCapacity>=n){chosen=f;break;}}
            if(chosen==null){for(var it=frames.iterator();it.hasNext();){var old=it.next();if(old.fence==0&&old.tileCapacity<n){old.close();it.remove();}}if(frames.size()>=3)return false;chosen=new Frame(n,budget);frames.add(chosen);}
            frame=chosen;
            if(paintEnabled&&frame.paint==null){
                var lease=budget.tryReserve(COMMAND_BYTES);
                if(lease!=null){try{frame.paint=buffer(COMMAND_BYTES,GpuBuffer.USAGE_INDIRECT_PARAMETERS);frame.paintLease=lease;}catch(RuntimeException e){lease.close();throw e;}}
            }
            frame.argAt=frame.idAt=frame.instanceAt=0;
            resize(w,h,budget);
            glActiveTexture(GL_TEXTURE0);
            var matrix=new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix);
            var data=MemoryUtil.memAlloc(bounds.size()*32).order(ByteOrder.nativeOrder());
            try {for(var b:bounds){putBounds(data,b,camera.pos.x,camera.pos.y,camera.pos.z);}data.flip();upload(frame.bounds,0,data);}finally{MemoryUtil.memFree(data);}
            profile.begin();profile.point(0);
            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT|GL_TEXTURE_FETCH_BARRIER_BIT);
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,depth.glId());glBindSampler(0,sampler);glUseProgram(reduce);i(reduce,"SourceDepth",0);i(reduce,"CopyDepth",1);glUniform2i(u(reduce,"ViewSize"),w,h);
            for(int mip=0;mip<levels;mip++){
                glUniform2i(u(reduce,"SourceSize"),Math.max(1,paddedWidth>>Math.max(0,mip-1)),Math.max(1,paddedHeight>>Math.max(0,mip-1)));glUniform2i(u(reduce,"DestinationSize"),Math.max(1,paddedWidth>>mip),Math.max(1,paddedHeight>>mip));
                glBindImageTexture(0,pyramid,Math.max(0,mip-1),false,0,GL_READ_ONLY,GL_R32F);glBindImageTexture(1,pyramid,mip,false,0,GL_WRITE_ONLY,GL_R32F);
                i(reduce,"CopyDepth",mip==0?1:0);glDispatchCompute((Math.max(1,paddedWidth>>mip)+7)/8,(Math.max(1,paddedHeight>>mip)+7)/8,1);glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL_TEXTURE_FETCH_BARRIER_BIT);
            }
            profile.point(1);
            glUseProgram(test);glBindTexture(GL_TEXTURE_2D,pyramid);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,handle(frame.bounds));glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,handle(frame.visible));
            i(test,"Pyramid",0);i(test,"TileCount",bounds.size());i(test,"MaxMip",levels-1);i(test,"ZeroToOne",RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()?1:0);glUniform2i(u(test,"ViewSize"),w,h);
            try(var stack=org.lwjgl.system.MemoryStack.stackPush()){glUniformMatrix4fv(u(test,"ViewProjection"),false,matrix.get(stack.mallocFloat(16)));}
            glDispatchCompute((bounds.size()+63)/64,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            profile.point(2);testedTiles=bounds.size();return true;
        }catch(PlaneAtlas.BudgetUnavailable e){end();return false;}catch(RuntimeException e){fail(e);return false;}
    }
    static void putBounds(ByteBuffer out,AABB b,double x,double y,double z){
        double lx=b.minX-x,ly=b.minY-y,lz=b.minZ-z,hx=b.maxX-x,hy=b.maxY-y,hz=b.maxZ-z;
        double px=Math.max(Math.ulp((float)lx),Math.ulp((float)hx))*4+0.001;
        double py=Math.max(Math.ulp((float)ly),Math.ulp((float)hy))*4+0.001;
        double pz=Math.max(Math.ulp((float)lz),Math.ulp((float)hz))*4+0.001;
        out.putFloat((float)(lx-px)).putFloat((float)(ly-py)).putFloat((float)(lz-pz)).putFloat(0);
        out.putFloat((float)(hx+px)).putFloat((float)(hy+py)).putFloat((float)(hz+pz)).putFloat(0);
    }
    /** Prepare all page commands on the CPU, then filter them with one GPU dispatch. */
    GpuBufferSlice queue(IntBuffer first,IntBuffer counts,IntBuffer bases,org.lwjgl.PointerBuffer offsets,IntBuffer tileIds,boolean indexed,int n){return queue(first,counts,bases,offsets,tileIds,indexed,n,false);}
    GpuBufferSlice queue(IntBuffer first,IntBuffer counts,IntBuffer bases,org.lwjgl.PointerBuffer offsets,IntBuffer tileIds,boolean indexed,int n,boolean quads){
        if(frame==null)return null;int stride=indexed?20:16,bytes=n*stride;
        if(frame.argAt+bytes>COMMAND_BYTES||frame.idAt+n*8>META_BYTES)return null;
        if(commandData==null){commandData=MemoryUtil.memAlloc(COMMAND_BYTES).order(ByteOrder.nativeOrder());idData=MemoryUtil.memAlloc(META_BYTES).order(ByteOrder.nativeOrder());}
        int arg=frame.argAt;commandData.position(arg);idData.position(frame.idAt);
        for(int j=0;j<n;j++){
            idData.putInt(tileIds.get(j)).putInt(commandData.position()/4);
            commandData.putInt(quads?6:counts.get(j)).putInt(quads?counts.get(j):1).putInt(quads?0:indexed?(int)(offsets.get(j)/4):first.get(j));if(indexed)commandData.putInt(bases.get(j));commandData.putInt(quads?first.get(j):0);
        }
        frame.argAt+=bytes;frame.idAt+=n*8;drawCommands+=n;
        return frame.args.slice(arg,bytes);
    }
    void filterCommands(){
        if(frame==null||frame.idAt==0)return;
        try(var state=new State()){
            profile.point(3);
            commandData.limit(frame.argAt).position(0);idData.limit(frame.idAt).position(0);
            upload(frame.args,0,commandData);if(paintEnabled&&frame.paint!=null)upload(frame.paint,0,commandData);upload(frame.ids,0,idData);
            glUseProgram(commands);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,handle(frame.visible));glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,handle(frame.args));glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,handle(frame.ids));
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,handle(frame.paint==null?frame.args:frame.paint));i(commands,"Paint",paintEnabled&&frame.paint!=null?1:0);
            i(commands,"Count",frame.idAt/8);glDispatchCompute((frame.idAt/8+63)/64,1,1);glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);profile.point(4);
        }finally{commandData.clear();idData.clear();}
    }
    record PlaneDraw(GpuBufferSlice instances,GpuBufferSlice command){}
    PlaneDraw compact(GpuBufferSlice input,int count){
        var batch=compactAll(input,64*48,new int[]{count});return batch==null?null:batch[0];
    }
    PlaneDraw[] compactAll(GpuBufferSlice input,int stride,int[] counts){
        if(frame==null||counts.length==0)return null;
        int out=(frame.instanceAt+255)&~255,arg=frame.argAt,meta=(frame.idAt+15)&~15,n=counts.length;
        if(out+(long)n*stride>INSTANCE_BYTES||arg+n*16>COMMAND_BYTES||meta+n*16>META_BYTES)return null;
        frame.instanceAt=out+n*stride;frame.argAt+=n*16;frame.idAt+=n*16;
        var data=MemoryUtil.memAlloc(n*16).order(ByteOrder.nativeOrder());var groups=MemoryUtil.memAlloc(n*16).order(ByteOrder.nativeOrder());
        var result=new PlaneDraw[n];
        try(var state=new State()){
            profile.point(5);
            for(int g=0;g<n;g++){
                data.putInt(6).putInt(0).putInt(0).putInt(0);
                groups.putInt((int)((input.offset()+(long)g*stride)/16)).putInt((out+g*stride)/16).putInt((arg+g*16)/4).putInt(counts[g]);
                result[g]=new PlaneDraw(frame.instances.slice(out+(long)g*stride,64*48),frame.args.slice(arg+(long)g*16,16));if(!paintDrawing)planeCandidates+=counts[g];
            }
            data.flip();groups.flip();upload(frame.args,arg,data);upload(frame.ids,meta,groups);
            glUseProgram(planes);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,handle(frame.visible));glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,handle(frame.args));glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,handle(frame.instances));glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,handle(input.buffer()));glBindBufferBase(GL_SHADER_STORAGE_BUFFER,4,handle(frame.ids));
            i(planes,"Paint",paintDrawing?1:0);i(planes,"GroupBase",meta/16);glDispatchCompute(1,n,1);glMemoryBarrier(GL_UNIFORM_BARRIER_BIT|GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);profile.point(6);
            return result;
        }finally{MemoryUtil.memFree(data);MemoryUtil.memFree(groups);}
    }
    void end(){if(frame!=null){profile.end();frame.fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);frame=null;}}
    private void init(){if(reduce!=0)return;reduce=program("hiz_pyramid");test=program("hiz_test");commands=program("hiz_commands");planes=program("hiz_planes");sampler=glGenSamplers();glSamplerParameteri(sampler,GL_TEXTURE_MIN_FILTER,GL_NEAREST_MIPMAP_NEAREST);glSamplerParameteri(sampler,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glSamplerParameteri(sampler,GL_TEXTURE_COMPARE_MODE,GL_NONE);}
    private void resize(int w,int h,ByteBudget budget){
        if(pyramid!=0&&width==w&&height==h)return;
        int pw=1,ph=1;while(pw<w)pw*=2;while(ph<h)ph*=2;int lv=1;for(int size=Math.max(pw,ph);size>1;size>>=1)lv++;
        long bytes=0;for(int m=0;m<lv;m++)bytes+=4L*Math.max(1,pw>>m)*Math.max(1,ph>>m);
        var lease=budget.tryReserve(bytes);if(lease==null)throw new PlaneAtlas.BudgetUnavailable();
        int replacement=glGenTextures();try{glBindTexture(GL_TEXTURE_2D,replacement);glTexStorage2D(GL_TEXTURE_2D,lv,GL_R32F,pw,ph);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,lv-1);}catch(RuntimeException e){glDeleteTextures(replacement);lease.close();throw e;}
        int old=pyramid;var oldLease=textureMemory;if(old!=0)glDeleteTextures(old);if(oldLease!=null)RenderSystem.queueFencedTask(oldLease::close);pyramid=replacement;textureMemory=lease;width=w;height=h;paddedWidth=pw;paddedHeight=ph;levels=lv;
    }
    int program(String name){
        int shader=glCreateShader(GL_COMPUTE_SHADER),p=glCreateProgram();
        try(var in=HiZ.class.getResourceAsStream("/assets/faraway/shaders/compute/"+name+".comp")){
            if(in==null)throw new IllegalStateException("Missing Hi-Z shader "+name);glShaderSource(shader,new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));glCompileShader(shader);if(glGetShaderi(shader,GL_COMPILE_STATUS)==0)throw new IllegalStateException(glGetShaderInfoLog(shader));glAttachShader(p,shader);glLinkProgram(p);if(glGetProgrami(p,GL_LINK_STATUS)==0)throw new IllegalStateException(glGetProgramInfoLog(p));return p;
        }catch(Exception e){glDeleteProgram(p);throw new IllegalStateException("Hi-Z shader "+name,e);}finally{glDeleteShader(shader);}
    }
    private int u(int p,String name){return uniforms.computeIfAbsent(p+":"+name,k->glGetUniformLocation(p,name));}
    private void i(int p,String name,int value){glUniform1i(u(p,name),value);}
    private static int handle(GpuBuffer b){return ((GlBuffer)b).handle();}
    private static void upload(GpuBuffer b,long offset,ByteBuffer data){glBindBuffer(GL_COPY_WRITE_BUFFER,handle(b));glBufferSubData(GL_COPY_WRITE_BUFFER,offset,data);}
    void fail(RuntimeException e){close();unavailable=true;dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.warn("Hi-Z disabled; direct drawing retained",e);}
    public void close(){
        end();profile.close();counters.close();
        if(commandData!=null){MemoryUtil.memFree(commandData);MemoryUtil.memFree(idData);commandData=idData=null;}
        var retired=new ArrayList<>(frames);frames.clear();var lease=textureMemory;textureMemory=null;
        if(!retired.isEmpty()||lease!=null)RenderSystem.queueFencedTask(()->{for(var f:retired)f.close();if(lease!=null)lease.close();});
        if(pyramid!=0)glDeleteTextures(pyramid);pyramid=0;
        for(int p:new int[]{reduce,test,commands,planes})if(p!=0)glDeleteProgram(p);reduce=test=commands=planes=0;
        if(sampler!=0)glDeleteSamplers(sampler);sampler=0;uniforms.clear();unavailable=false;
    }
    /** Restore every GL binding touched by compute; engine state caches remain valid. */
    static final class State implements AutoCloseable {
        final int program=glGetInteger(GL_CURRENT_PROGRAM),active=glGetInteger(GL_ACTIVE_TEXTURE),read=glGetInteger(GL_COPY_READ_BUFFER_BINDING),copy=glGetInteger(GL_COPY_WRITE_BUFFER_BINDING),generic=glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        final int texture,sampler;final int[] ssbo=new int[5],images=new int[2],mips=new int[2],access=new int[2],format=new int[2],layered=new int[2],layer=new int[2];final long[] start=new long[5],size=new long[5];
        State(){glActiveTexture(GL_TEXTURE0);texture=glGetInteger(GL_TEXTURE_BINDING_2D);sampler=glGetIntegeri(GL_SAMPLER_BINDING,0);for(int i=0;i<5;i++){ssbo[i]=glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,i);start[i]=glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START,i);size[i]=glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE,i);}for(int i=0;i<2;i++){images[i]=glGetIntegeri(GL_IMAGE_BINDING_NAME,i);mips[i]=glGetIntegeri(GL_IMAGE_BINDING_LEVEL,i);access[i]=glGetIntegeri(GL_IMAGE_BINDING_ACCESS,i);format[i]=glGetIntegeri(GL_IMAGE_BINDING_FORMAT,i);layered[i]=glGetIntegeri(GL_IMAGE_BINDING_LAYERED,i);layer[i]=glGetIntegeri(GL_IMAGE_BINDING_LAYER,i);}}
        public void close(){for(int i=0;i<5;i++){if(ssbo[i]!=0&&size[i]>0)glBindBufferRange(GL_SHADER_STORAGE_BUFFER,i,ssbo[i],start[i],size[i]);else glBindBufferBase(GL_SHADER_STORAGE_BUFFER,i,ssbo[i]);}glBindBuffer(GL_SHADER_STORAGE_BUFFER,generic);for(int i=0;i<2;i++)glBindImageTexture(i,images[i],mips[i],layered[i]!=0,layer[i],access[i],format[i]);glBindBuffer(GL_COPY_READ_BUFFER,read);glBindBuffer(GL_COPY_WRITE_BUFFER,copy);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture);glBindSampler(0,sampler);glActiveTexture(active);glUseProgram(program);}
    }
}
