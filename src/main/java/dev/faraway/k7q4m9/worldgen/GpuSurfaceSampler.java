package dev.faraway.k7q4m9.worldgen;

import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL43C.*;

/** Experimental OpenGL compute height field. All GL access belongs to the render thread. */
public final class GpuSurfaceSampler {
    public record Measurement(int[] heights,long queueNs,long submitNs,long completionWaitNs,long gpuNs,long readbackNs,long totalNs) {}
    private record Job(int x,int z,int width,int depth,int spacing,long seed,int min,int max,int sea,long createdNs,CompletableFuture<Measurement> result) {}
    private record Running(Job job,int buffer,long fence,int queryStart,int queryEnd,long dispatchedNs,long queueNs,long submitNs) {}
    private static final ArrayBlockingQueue<Job> queue=new ArrayBlockingQueue<>(32);
    private static final java.util.ArrayList<Running> running=new java.util.ArrayList<>();
    private static volatile boolean unsupported;
    private static int program;
    private GpuSurfaceSampler() {}
    public static boolean unavailable() { return unsupported; }
    public static Measurement sample(int x,int z,int width,int depth,int spacing,long seed,int min,int max,int sea,BooleanSupplier cancelled) {
        if(unsupported) return null;
        var future=new CompletableFuture<Measurement>();
        var job=new Job(x,z,width,depth,spacing,seed,min,max,sea,System.nanoTime(),future);
        try {
            while(!queue.offer(job,20,TimeUnit.MILLISECONDS)) {
                if(cancelled.getAsBoolean()) throw new CancellationException();
                if(unsupported) return null;
            }
            while(!cancelled.getAsBoolean()) {
                if(unsupported) return null;
                try { return future.get(20,TimeUnit.MILLISECONDS); }
                catch(TimeoutException waiting) { }
                catch(ExecutionException failure) { return null; }
            }
            throw new CancellationException();
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException(); }
        finally { if(!future.isDone()) future.cancel(false); }
    }
    public static void close() {
        for(var task:running) { task.job().result().cancel(false); release(task); }
        running.clear(); Job job; while((job=queue.poll())!=null) job.result().cancel(false);
        if(program!=0) { glDeleteProgram(program); program=0; }
    }
    public static void pump() {
        if(unsupported || (queue.isEmpty() && running.isEmpty())) return;
        try {
            var caps=GL.getCapabilities();
            if(!caps.OpenGL43 && !(caps.GL_ARB_compute_shader && caps.GL_ARB_shader_storage_buffer_object && caps.GL_ARB_shading_language_420pack)) throw new IllegalStateException("GPU compute extensions are unavailable");
            if(program==0) initialize();
            int oldProgram=glGetInteger(GL_CURRENT_PROGRAM),oldBuffer=glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING),oldBinding=glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,0);
            try {
                for(var iterator=running.iterator();iterator.hasNext();) {
                    var task=iterator.next();
                    int status=glClientWaitSync(task.fence(),0,0);
                    if(status==GL_WAIT_FAILED) throw new IllegalStateException("GPU surface fence failed");
                    if(status!=GL_ALREADY_SIGNALED && status!=GL_CONDITION_SATISFIED) continue;
                    if(!task.job().result().isCancelled() && glGetQueryObjecti(task.queryEnd(),GL_QUERY_RESULT_AVAILABLE)==0) continue;
                    try {
                        if(!task.job().result().isCancelled()) {
                            long completionNs=System.nanoTime();
                            long gpuNs=glGetQueryObjecti64(task.queryEnd(),GL_QUERY_RESULT)-glGetQueryObjecti64(task.queryStart(),GL_QUERY_RESULT);
                            int[] heights=new int[task.job().width()*task.job().depth()];
                            long readStart=System.nanoTime();
                            glBindBuffer(GL_SHADER_STORAGE_BUFFER,task.buffer()); glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,heights);
                            long readEnd=System.nanoTime();
                            task.job().result().complete(new Measurement(heights,task.queueNs(),task.submitNs(),completionNs-task.dispatchedNs(),gpuNs,readEnd-readStart,readEnd-task.job().createdNs()));
                        }
                    } finally { release(task); iterator.remove(); }
                }
                glUseProgram(program);
                // Bound both submission count and in-flight work; never wait on a GPU fence here.
                for(int submitted=0;submitted<2 && running.size()<4;submitted++) {
                    var job=queue.poll(); if(job==null) break;
                    if(job.result().isCancelled()) continue;
                    long submitStart=System.nanoTime();
                    int buffer=glGenBuffers(),queryStart=glGenQueries(),queryEnd=glGenQueries();
                    try {
                        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffer); glBufferData(GL_SHADER_STORAGE_BUFFER,(long)job.width()*job.depth()*4,GL_STREAM_READ);
                        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,buffer);
                        glUniform2i(glGetUniformLocation(program,"Origin"),job.x(),job.z());
                        glUniform2i(glGetUniformLocation(program,"Size"),job.width(),job.depth());
                        glUniform1i(glGetUniformLocation(program,"Spacing"),job.spacing());
                        glUniform2ui(glGetUniformLocation(program,"Seed"),(int)job.seed(),(int)(job.seed()>>>32));
                        glUniform3i(glGetUniformLocation(program,"Limits"),job.min(),job.max(),job.sea());
                        glQueryCounter(queryStart,GL_TIMESTAMP);
                        glDispatchCompute((job.width()+7)/8,(job.depth()+7)/8,1);
                        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
                        glQueryCounter(queryEnd,GL_TIMESTAMP);
                        long fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0),submittedNs=System.nanoTime();
                        running.add(new Running(job,buffer,fence,queryStart,queryEnd,submittedNs,submitStart-job.createdNs(),submittedNs-submitStart));
                    } catch(Throwable failure) { glDeleteQueries(queryStart); glDeleteQueries(queryEnd); glDeleteBuffers(buffer); job.result().completeExceptionally(failure); throw failure; }
                }
            } finally { glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,oldBinding); glBindBuffer(GL_SHADER_STORAGE_BUFFER,oldBuffer); glUseProgram(oldProgram); }
        } catch(Throwable failure) {
            unsupported=true;
            for(var task:running) { task.job().result().completeExceptionally(failure); try { release(task); } catch(Throwable ignored) {} }
            running.clear(); Job job; while((job=queue.poll())!=null) job.result().completeExceptionally(failure);
            dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.warn("GPU surface sampling unavailable; using CPU",failure);
        }
    }
    private static void release(Running task) {
        glDeleteSync(task.fence()); glDeleteBuffers(task.buffer());
        glDeleteQueries(task.queryStart()); glDeleteQueries(task.queryEnd());
    }
    private static void initialize() throws java.io.IOException {
        String source;
        try(var in=GpuSurfaceSampler.class.getResourceAsStream("/assets/faraway/shaders/surface.comp")) {
            if(in==null) throw new java.io.IOException("Missing GPU surface shader");
            source=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        int shader=glCreateShader(GL_COMPUTE_SHADER);
        try {
            glShaderSource(shader,source); glCompileShader(shader);
            if(glGetShaderi(shader,GL_COMPILE_STATUS)==0) throw new IllegalStateException(glGetShaderInfoLog(shader));
            int linked=glCreateProgram();
            try {
                glAttachShader(linked,shader); glLinkProgram(linked);
                if(glGetProgrami(linked,GL_LINK_STATUS)==0) throw new IllegalStateException(glGetProgramInfoLog(linked));
                program=linked;
            } finally { if(program!=linked) glDeleteProgram(linked); }
            dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.info("FarAway GPU compute ready: renderer={} OpenGL={}",glGetString(GL_RENDERER),glGetString(GL_VERSION));
        } finally { glDeleteShader(shader); }
    }
}
