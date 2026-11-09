package dev.faraway.k7q4m9.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import dev.faraway.k7q4m9.streaming.ByteBudget;
import java.nio.*;
import java.util.*;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL43C.*;

/** Configurable asynchronous HUD sampling. Only completed, dedicated 64-byte result buffers are read. */
public final class HiZCounters implements AutoCloseable {
    public record Snapshot(long preTriangles,long postTriangles,long preSamples,long postSamples,long activeTriangles,long frustumTriangles,long drawCalls,long prePlanes,long preVoxels,long postPlanes,long postVoxels) {
        public double triangleCulled(){return percent(preTriangles,postTriangles);}
        public double sampleCulled(){return percent(preSamples,postSamples);}
        private static double percent(long pre,long post){return pre==0?0:100.0*Math.max(0,pre-post)/pre;}
    }
    private int pollRate=4;
    private long periodNanos=250_000_000L;
    void setPollRate(int hz){
        if(hz<1)throw new IllegalArgumentException("Hi-Z poll rate must be positive");
        if(hz!=pollRate){pollRate=hz;periodNanos=Math.max(1,1_000_000_000L/hz);nextSample=0;}
    }
    private static final class Job implements AutoCloseable {
        GpuBuffer input,output;ByteBudget.Reservation lease;int capacity;long fence,sequence,activeTriangles,frustumTriangles,drawCalls;
        Job(int n,ByteBudget budget){
            capacity=n;lease=budget.tryReserve(24L*n+64);if(lease==null)throw new PlaneAtlas.BudgetUnavailable();
            try {input=RenderSystem.getDevice().createBuffer(()->"FarAway Hi-Z HUD weights",GpuBuffer.USAGE_COPY_DST,24L*n);output=RenderSystem.getDevice().createBuffer(()->"FarAway Hi-Z HUD result",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,64);}
            catch(RuntimeException e){close();throw e;}
        }
        public void close(){if(fence!=0)glDeleteSync(fence);if(input!=null)input.close();if(output!=null)output.close();if(lease!=null)lease.close();}
    }
    private final ArrayList<Job> jobs=new ArrayList<>();
    boolean suppressed;private long sequence,lastRead;
    private long nextSample;private int program,count;private boolean disabled;
    private long[] fixed=new long[0],tested=new long[0],samples;
    private Job collecting;
    private Snapshot snapshot;
    public Snapshot snapshot(){return snapshot;}
    boolean due(){return !disabled&&System.nanoTime()>=nextSample;}
    boolean collecting(){return collecting!=null&&!suppressed;}
    void begin(HiZ owner,long[] samples,ByteBudget budget){
        collecting=null;if(disabled||System.nanoTime()<nextSample)return;
        try(var state=new HiZ.State()){
            poll();nextSample=System.nanoTime()+periodNanos;if(samples==null)return;
            if(program==0)program=owner.program("hiz_counters");
            count=samples.length;this.samples=samples;
            if(fixed.length<count){fixed=new long[count];tested=new long[count];}else {Arrays.fill(fixed,0,count,0);Arrays.fill(tested,0,count,0);}
            for(var job:jobs)if(job.fence==0&&job.capacity>=count){collecting=job;return;}
            for(var it=jobs.iterator();it.hasNext();){var old=it.next();if(old.fence==0){old.close();it.remove();}}
            if(jobs.size()<3){collecting=new Job(Math.max(1,count),budget);jobs.add(collecting);}
        }catch(PlaneAtlas.BudgetUnavailable pressure){collecting=null;}catch(RuntimeException e){disable(e);}
    }
    void plane(int id) { if(collecting!=null && samples!=null && id>=0 && id<count)samples[id]|=Long.MIN_VALUE; }
    void add(int id,long triangles,boolean filtered){
        if(collecting==null||suppressed||id<0||id>=count||triangles<=0)return;
        if(filtered)tested[id]+=triangles;else fixed[id]+=triangles;
    }
    void finish(GpuBuffer visibility){finish(visibility,0,0,0);}
    void finish(GpuBuffer visibility,long active,long frustum,long calls){
        var job=collecting;collecting=null;if(job==null)return;job.sequence=++sequence;job.activeTriangles=active;job.frustumTriangles=frustum;job.drawCalls=calls;
        var data=MemoryUtil.memAlloc(count*24).order(ByteOrder.nativeOrder());
        try(var state=new HiZ.State()){
            for(int i=0;i<count;i++)data.putLong(fixed[i]).putLong(tested[i]).putLong(samples[i]);data.flip();
            glBindBuffer(GL_COPY_WRITE_BUFFER,((GlBuffer)job.input).handle());glBufferSubData(GL_COPY_WRITE_BUFFER,0,data);
            glUseProgram(program);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,((GlBuffer)(visibility==null?job.input:visibility)).handle());glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,((GlBuffer)job.input).handle());glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,((GlBuffer)job.output).handle());
            glUniform1i(glGetUniformLocation(program,"TileCount"),count);glUniform1i(glGetUniformLocation(program,"UseVisibility"),visibility==null?0:1);
            glDispatchCompute(1,1,1);glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);job.fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);
        }catch(RuntimeException e){disable(e);}finally{MemoryUtil.memFree(data);samples=null;}
    }
    private void poll(){
        // Poll only on the configured schedule; never ask for an unfinished buffer's contents.
        if(System.nanoTime()<nextSample)return;
        for(var job:jobs)if(job.fence!=0){int status=glClientWaitSync(job.fence,0,0);if(status==GL_WAIT_FAILED)throw new IllegalStateException("Hi-Z HUD fence failed");if(status!=GL_ALREADY_SIGNALED&&status!=GL_CONDITION_SATISFIED)continue;
            var data=MemoryUtil.memAlloc(64).order(ByteOrder.nativeOrder());
            try{glBindBuffer(GL_COPY_READ_BUFFER,((GlBuffer)job.output).handle());glGetBufferSubData(GL_COPY_READ_BUFFER,0,data);if(job.sequence>lastRead){snapshot=new Snapshot(data.getLong(0),data.getLong(8),data.getLong(16),data.getLong(24),job.activeTriangles,job.frustumTriangles,job.drawCalls,data.getLong(32),data.getLong(40),data.getLong(48),data.getLong(56));lastRead=job.sequence;}}
            finally{MemoryUtil.memFree(data);}glDeleteSync(job.fence);job.fence=0;
        }
    }
    private void disable(RuntimeException e){close();disabled=true;dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.warn("Hi-Z HUD counters unavailable; culling remains enabled",e);}
    public void close(){collecting=null;samples=null;snapshot=null;nextSample=0;disabled=suppressed=false;sequence=lastRead=0;
        var retired=new ArrayList<>(jobs);jobs.clear();if(!retired.isEmpty())RenderSystem.queueFencedTask(()->retired.forEach(Job::close));
        if(program!=0)glDeleteProgram(program);program=0;
    }
}
