package dev.faraway.k7q4m9.diagnostics;
import static org.lwjgl.opengl.GL33C.*;
/** World-render timings. Pending timer queries are polled without waiting. */
public final class AdvancedRenderTiming {
    private static final int[] queries=new int[8];
    private static final boolean[] pending=new boolean[8];
    private static final long[] sequence=new long[8];
    private static long serial,lastRead,cpuStart,submissionStart,submissionTotal;
    private static int active=-1,submissionDepth;
    private static boolean running,unavailable;
    private static long cpuNs,submissionNs,gpuNs=-1;
    private static final RollingTimingWindow cpuAverage=new RollingTimingWindow(),gpuAverage=new RollingTimingWindow(),submissionAverage=new RollingTimingWindow();
    private static final RollingTimingWindow cpuAverage10=new RollingTimingWindow(10_000_000_000L),gpuAverage10=new RollingTimingWindow(10_000_000_000L),submissionAverage10=new RollingTimingWindow(10_000_000_000L);
    public static double cpuAverage10Ns(){return cpuAverage10.average(System.nanoTime());}
    public static double gpuAverage10Ns(){return gpuAverage10.average(System.nanoTime());}
    public static double submissionAverage10Ns(){return submissionAverage10.average(System.nanoTime());}
    public static double cpuAverageNs(){return cpuAverage.average(System.nanoTime());}
    public static double gpuAverageNs(){return gpuAverage.average(System.nanoTime());}
    public static double submissionAverageNs(){return submissionAverage.average(System.nanoTime());}
    private AdvancedRenderTiming() {}
    public static long cpuNs(){return cpuNs;}public static long submissionNs(){return submissionNs;}public static long gpuNs(){return gpuNs;}
    public static void begin(boolean enabled) {
        if(running)end();
        if(!enabled){release();return;}
        cpuStart=System.nanoTime();submissionTotal=0;submissionDepth=0;running=true;
        if(unavailable)return;
        try {
            var caps=org.lwjgl.opengl.GL.getCapabilities();if(!caps.OpenGL33&&!caps.GL_ARB_timer_query){unavailable=true;return;}
            for(int i=0;i<queries.length;i++)if(pending[i]&&glGetQueryObjecti(queries[i],GL_QUERY_RESULT_AVAILABLE)!=0) {
                long value=glGetQueryObjecti64(queries[i],GL_QUERY_RESULT);
                long sampleTime=System.nanoTime();gpuAverage.add(sampleTime,value);gpuAverage10.add(sampleTime,value);
                if(sequence[i]>lastRead){gpuNs=value;lastRead=sequence[i];}pending[i]=false;
            }
            if(glGetQueryi(GL_TIME_ELAPSED,GL_CURRENT_QUERY)!=0)return;
            for(int i=0;i<queries.length;i++)if(!pending[i]) {
                if(queries[i]==0)queries[i]=glGenQueries();
                glBeginQuery(GL_TIME_ELAPSED,queries[i]);active=i;break;
            }
        }catch(RuntimeException e){unavailable=true;dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.warn("Advanced HUD GPU timer unavailable",e);}
    }
    public static void beginSubmission(){if(running&&submissionDepth++==0)submissionStart=System.nanoTime();}
    public static void endSubmission(){if(running&&submissionDepth>0&&--submissionDepth==0)submissionTotal+=System.nanoTime()-submissionStart;}
    public static void end() {
        if(!running)return;
        if(active>=0){glEndQuery(GL_TIME_ELAPSED);pending[active]=true;sequence[active]=++serial;active=-1;}
        long now=System.nanoTime();cpuNs=now-cpuStart;submissionNs=submissionTotal;
        cpuAverage.add(now,cpuNs);submissionAverage.add(now,submissionNs);cpuAverage10.add(now,cpuNs);submissionAverage10.add(now,submissionNs);running=false;
    }
    public static void reset() { release(); }
    private static void release() {
        if(active>=0){glEndQuery(GL_TIME_ELAPSED);active=-1;}
        for(int i=0;i<queries.length;i++){if(queries[i]!=0)glDeleteQueries(queries[i]);queries[i]=0;pending[i]=false;}
        cpuAverage10.clear();gpuAverage10.clear();submissionAverage10.clear();
        cpuAverage.clear();gpuAverage.clear();submissionAverage.clear();
        running=false;gpuNs=-1;lastRead=serial=cpuNs=submissionNs=0;
    }
}
