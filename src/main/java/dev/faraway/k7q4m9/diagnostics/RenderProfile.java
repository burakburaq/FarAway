package dev.faraway.k7q4m9.diagnostics;

import dev.faraway.k7q4m9.client.FarAwayClient;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.HashMap;

/** Low-volume, bounded profiling. All mutations are on the render thread. No disk writes per frame. */
public final class RenderProfile {
    public enum Stage { PREPARE, GPU_PUMP, VISIBILITY, TERRAIN_SUBMIT, PLANE_BUILD, PLANE_SUBMIT, WATER_SUBMIT, DEBUG_SUBMIT, UPLOAD, UPLOAD_BUFFER, UPLOAD_TEXTURE, DYNAMIC_UNIFORMS, INSTANCE_FENCE, INSTANCE_MAP, INSTANCE_WRITE, COVERAGE }
    private static final Stage[] STAGES=Stage.values();
    public static final RenderProfile INSTANCE=new RenderProfile();
    private final TimingWindow intervals=new TimingWindow(16384),levelCpu=new TimingWindow(16384);
    private final TimingWindow[] cpu=new TimingWindow[STAGES.length];
    private final TimingWindow[] gpu={new TimingWindow(2048),new TimingWindow(2048),new TimingWindow(2048),new TimingWindow(2048)};
    private final long[] stages=new long[STAGES.length];
    private final Worst[] worst={new Worst(),new Worst(),new Worst(),new Worst(),new Worst()};
    private final java.lang.management.ThreadMXBean threads=ManagementFactory.getThreadMXBean();
    private final com.sun.management.ThreadMXBean allocations=threads instanceof com.sun.management.ThreadMXBean bean?bean:null;
    private final java.util.List<java.lang.management.GarbageCollectorMXBean> collectors=ManagementFactory.getGarbageCollectorMXBeans();
    private final HashMap<Long,long[]> workerBaseline=new HashMap<>();
    private long start,previousEnd,windowStart,allocatedStart,allocatedBytes,frames,over16,over33,gcCount,gcMs;
    private long uploads,uploadBytes,queueMax,queueBytesMax,activeSum,activeMax,workerLimit,generatingFrames,enabledFrames;
    private long considered,rejectedLevel,rejectedCoverage,rejectedBehind,rejectedScreen,rejectedFrustum,drawn,drawCalls,pipelineChanges;
    private long gpuSkipped,gpuInvalid,buildQueueMax,inFlightMax;
    private long frameUploads,frameUploadBytes;
    private int currentActive,currentQueue,currentBuildQueue,currentInFlight;
    private long renderThreadAllocationBaseline;
    private final GcEventProfile gcEvents=new GcEventProfile();
    private boolean allocationSupported,cpuSupported,inside;
    private RenderProfile() {
        for(int i=0;i<cpu.length;i++) cpu[i]=new TimingWindow(4096);
        try {
            if(allocations!=null && allocations.isThreadAllocatedMemorySupported()) {
                if(!allocations.isThreadAllocatedMemoryEnabled()) allocations.setThreadAllocatedMemoryEnabled(true);
                allocationSupported=allocations.isThreadAllocatedMemoryEnabled();
            }
            if(threads.isThreadCpuTimeSupported()) { if(!threads.isThreadCpuTimeEnabled()) threads.setThreadCpuTimeEnabled(true); cpuSupported=threads.isThreadCpuTimeEnabled(); }
        } catch(RuntimeException unavailable) { FarAwayClient.LOGGER.warn("FarAway allocation/thread CPU profiling partially unavailable",unavailable); }
    }
    public void begin() {
        long now=System.nanoTime();
        if(windowStart==0) { windowStart=now; gcCount=gcCount(); gcMs=gcMs(); workerSnapshot(); renderThreadAllocationBaseline=allocated(); gcEvents.start(); }
        Arrays.fill(stages,0); frameUploads=frameUploadBytes=0; start=now; inside=true; allocatedStart=allocated();
    }
    public void add(Stage stage,long ns) { if(inside) stages[stage.ordinal()]+=Math.max(0,ns); }
    public void uploaded(long bytes) { if(inside) { uploads++; uploadBytes+=bytes; frameUploads++; frameUploadBytes+=bytes; } }
    public void workload(boolean enabled,int active,int limit,int queue,long bytes,int buildQueue,int inFlight) {
        if(!inside) return;
        currentActive=active; currentQueue=queue; currentBuildQueue=buildQueue; currentInFlight=inFlight;
        if(enabled) enabledFrames++; if(active>0) generatingFrames++;
        activeSum+=active; activeMax=Math.max(activeMax,active); workerLimit=limit;
        queueMax=Math.max(queueMax,queue); queueBytesMax=Math.max(queueBytesMax,bytes); buildQueueMax=Math.max(buildQueueMax,buildQueue); inFlightMax=Math.max(inFlightMax,inFlight);
    }
    public void visibility(long total,long level,long coverage,long behind,long screen,long frustum,long visible) {
        considered+=total; rejectedLevel+=level; rejectedCoverage+=coverage; rejectedBehind+=behind; rejectedScreen+=screen; rejectedFrustum+=frustum; drawn+=visible;
    }
    public void draws(int calls,int changes) { drawCalls+=calls; pipelineChanges+=changes; }
    public void gpu(int stage,long ns) { if(ns>=0) gpu[stage].add(ns); else gpuInvalid++; }
    public void skippedGpu() { gpuSkipped++; }
    public void end() {
        if(!inside) return;
        long now=System.nanoTime(),duration=now-start,interval=previousEnd==0?0:now-previousEnd;
        previousEnd=now; frames++; levelCpu.add(duration);
        if(interval>0) { intervals.add(interval); if(interval>16_666_667L) over16++; if(interval>33_333_333L) over33++; }
        long allocation=allocated(); allocation=allocationSupported && allocation>=allocatedStart && allocatedStart>=0?allocation-allocatedStart:0;
        allocatedBytes+=allocation;
        for(int i=0;i<stages.length;i++) cpu[i].add(stages[i]);
        long score=Math.max(interval,duration);
        for(int i=0;i<worst.length;i++) if(score>worst[i].score) {
            Worst reuse=worst[worst.length-1];
            for(int j=worst.length-1;j>i;j--) worst[j]=worst[j-1];
            worst[i]=reuse; reuse.score=score; reuse.interval=interval; reuse.cpu=duration; reuse.allocated=allocation; reuse.frame=frames; reuse.active=currentActive; reuse.queue=currentQueue; reuse.buildQueue=currentBuildQueue; reuse.inFlight=currentInFlight; reuse.uploads=frameUploads; reuse.uploadBytes=frameUploadBytes;
            System.arraycopy(stages,0,reuse.stages,0,stages.length); break;
        }
        inside=false;
        if(now-windowStart>=10_000_000_000L) flush(now);
    }
    private long allocated() { return allocationSupported?allocations.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1; }
    private long gcCount() { long n=0; for(var bean:collectors) n+=Math.max(0,bean.getCollectionCount()); return n; }
    private long gcMs() { long n=0; for(var bean:collectors) n+=Math.max(0,bean.getCollectionTime()); return n; }
    private long[] workerSnapshot() {
        long cpuDelta=0,allocDelta=0; var live=new java.util.HashSet<Long>();
        for(long id:threads.getAllThreadIds()) {
            var info=threads.getThreadInfo(id); if(info==null || !info.getThreadName().startsWith("FarAway terrain")) continue;
            live.add(id); long cpu=cpuSupported?threads.getThreadCpuTime(id):-1,alloc=allocationSupported?allocations.getThreadAllocatedBytes(id):-1;
            long[] old=workerBaseline.put(id,new long[]{cpu,alloc});
            if(cpu>=0) cpuDelta+=Math.max(0,cpu-(old==null?0:Math.max(0,old[0])));
            if(alloc>=0) allocDelta+=Math.max(0,alloc-(old==null?0:Math.max(0,old[1])));
        }
        workerBaseline.keySet().retainAll(live); return new long[]{cpuDelta,allocDelta};
    }
    private void flush(long now) {
        long snapshotStart=System.nanoTime();
        var log=new ReportLogger(); double seconds=(now-windowStart)/1e9;
        long[] workers=workerSnapshot(); long collections=gcCount(),collectionMs=gcMs();
        log.info("FarAway frame profile frames={} intervalEndToEnd=[{}] levelRenderCpu=[{}] over16ms={} over33ms={}",frames,intervals.summary(),levelCpu.summary(),over16,over33);
        for(int i=0;i<STAGES.length;i++) log.info("FarAway CPU stage {} [{}]",STAGES[i],cpu[i].summary());
        String[] names={"terrain","plane","water","debug"};
        for(int i=0;i<gpu.length;i++) log.info("FarAway GPU stage {} [{}]",names[i],gpu[i].summary());
        var config=dev.faraway.k7q4m9.client.LodSettings.get();
        var client=net.minecraft.client.Minecraft.getInstance();
        log.info("FarAway profile context resolution={}x{} distanceChunks={} samplePx={} workerSetting={} performance={} gpuSampling={} wireframe={} previewMode={} freecam={}",client.getWindow().getWidth(),client.getWindow().getHeight(),config.renderDistance(),config.samplePixels(),config.workers(),config.performance(),config.gpuSurfaceSampling(),config.wireframe(),config.previewMode(),dev.faraway.k7q4m9.client.Freecam.active());
        log.info("FarAway workload enabledFrames={} generatingFrames={} activeWorkersAvg={} activeWorkersMax={} workerLimit={} workerCpuMs={} workerAllocatedBytes={} levelRenderAllocatedBytes={} levelRenderAllocatedBytesPerSec={} allocationSupported={} cpuSupported={} gcCollections={} gcTimeMs={} uploadTiles={} uploadBytes={} queueMax={} queueBytesMax={} buildQueueMax={} inFlightMax={} gpuSkipped={} gpuInvalid={}",enabledFrames,generatingFrames,(double)activeSum/Math.max(1,frames),activeMax,workerLimit,workers[0]/1e6,workers[1],allocatedBytes,(long)(allocatedBytes/seconds),allocationSupported,cpuSupported,collections-gcCount,collectionMs-gcMs,uploads,uploadBytes,queueMax,queueBytesMax,buildQueueMax,inFlightMax,gpuSkipped,gpuInvalid);
        log.info("FarAway visibility totals considered={} lodRejected={} coverageRejected={} behindRejected={} screenRejected={} frustumRejected={} visible={} drawCalls={} pipelineChanges={}",considered,rejectedLevel,rejectedCoverage,rejectedBehind,rejectedScreen,rejectedFrustum,drawn,drawCalls,pipelineChanges);
        for(var w:worst) if(w.score>0) {
            log.info("FarAway slow frame windowFrame={} intervalMs={} levelCpuMs={} outsideLevelMs={} levelAllocatedBytes={} activeWorkers={} uploadQueue={} buildQueue={} inFlight={} uploads={} uploadBytes={} stages=[{}]",w.frame,w.interval/1e6,w.cpu/1e6,Math.max(0,w.interval-w.cpu)/1e6,w.allocated,w.active,w.queue,w.buildQueue,w.inFlight,w.uploads,w.uploadBytes,new StageDetails(w.stages.clone()));
            w.score=0;
        }
        long threadAllocated=allocated();
        log.info("FarAway JVM profile renderThreadAllocatedBytes={} renderThreadAllocatedBytesPerSec={} gcEvents={} reportsDropped={}",allocationSupported?Math.max(0,threadAllocated-renderThreadAllocationBaseline):-1,allocationSupported?(long)(Math.max(0,threadAllocated-renderThreadAllocationBaseline)/seconds):-1,gcEvents.drain(),ReportLogger.DROPPED.get());
        renderThreadAllocationBaseline=threadAllocated;
        intervals.clear(); levelCpu.clear(); for(var stat:cpu) stat.clear(); for(var stat:gpu) stat.clear();
        frames=over16=over33=allocatedBytes=uploads=uploadBytes=queueMax=queueBytesMax=activeSum=activeMax=enabledFrames=generatingFrames=0;
        considered=rejectedLevel=rejectedCoverage=rejectedBehind=rejectedScreen=rejectedFrustum=drawn=drawCalls=pipelineChanges=gpuSkipped=gpuInvalid=buildQueueMax=inFlightMax=0;
        windowStart=now; gcCount=collections; gcMs=collectionMs;
        log.info("FarAway profiler snapshotCpuMs={}",(System.nanoTime()-snapshotStart)/1e6);
        log.submit();
    }
    public void reset() {
        inside=false; start=previousEnd=windowStart=0; gcEvents.stop();
        intervals.clear(); levelCpu.clear(); for(var stat:cpu) stat.clear(); for(var stat:gpu) stat.clear();
        frames=over16=over33=allocatedBytes=uploads=uploadBytes=queueMax=queueBytesMax=activeSum=activeMax=enabledFrames=generatingFrames=0;
        considered=rejectedLevel=rejectedCoverage=rejectedBehind=rejectedScreen=rejectedFrustum=drawn=drawCalls=pipelineChanges=gpuSkipped=gpuInvalid=buildQueueMax=inFlightMax=0;
        workerBaseline.clear(); for(var w:worst) w.score=0;
    }
    private record StageDetails(long[] values) {
        @Override public String toString() {
            var text=new StringBuilder();
            for(int i=0;i<STAGES.length;i++) if(values[i]>0) text.append(STAGES[i]).append('=').append(String.format(java.util.Locale.ROOT,"%.3fms ",values[i]/1e6));
            return text.toString();
        }
    }
    private static final class ReportLogger {
        static final java.util.concurrent.atomic.AtomicLong DROPPED=new java.util.concurrent.atomic.AtomicLong();
        private static final java.util.concurrent.ThreadPoolExecutor WRITER=new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(1),r -> {
            var thread=new Thread(r,"FarAway profile log"); thread.setDaemon(true); return thread;
        },(r,executor) -> DROPPED.incrementAndGet());
        private final java.util.ArrayList<Line> lines=new java.util.ArrayList<>(32);
        void info(String format,Object... args) { lines.add(new Line(format,args)); }
        void submit() { WRITER.execute(() -> { for(var line:lines) FarAwayClient.LOGGER.info(line.format(),line.args()); }); }
        private record Line(String format,Object[] args) {}
    }
    private static final class Worst { long score,interval,cpu,allocated,frame,uploads,uploadBytes; int active,queue,buildQueue,inFlight; final long[] stages=new long[STAGES.length]; }
}
