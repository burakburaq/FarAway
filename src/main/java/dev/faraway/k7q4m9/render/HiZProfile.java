package dev.faraway.k7q4m9.render;

import java.util.Arrays;
import java.util.Locale;
import static org.lwjgl.opengl.GL33C.*;

/** Optional timestamp samples. Neither query polling nor visibility testing blocks for GPU results. */
final class HiZProfile implements AutoCloseable {
    private final int[] queries=new int[64];
    private final boolean[] pending=new boolean[8],written=new boolean[8];
    private final long[] issued=new long[8],sum=new long[4];
    private int active=-1;
    private long frame,samples;
    private boolean disabled;
    void begin() {
        active=-1;if(disabled)return;frame++;
        try {
            for(int slot=0;slot<8;slot++)if(pending[slot]&&frame-issued[slot]>=4&&glGetQueryObjecti(queries[slot*8+7],GL_QUERY_RESULT_AVAILABLE)!=0) {
                // Last timestamp available: all earlier timestamps in this ordered command stream are ready.
                long[] values=new long[8];for(int i=0;i<8;i++)values[i]=glGetQueryObjecti64(queries[slot*8+i],GL_QUERY_RESULT);
                int[][] stages={{0,1},{1,2},{3,4},{5,6}};
                for(int i=0;i<4;i++)sum[i]+=Math.max(0,values[stages[i][1]]-values[stages[i][0]]);
                samples++;pending[slot]=false;
            }
            if((frame&15)!=0)return;
            if(queries[0]==0)for(int i=0;i<queries.length;i++)queries[i]=glGenQueries();
            for(int slot=0;slot<8;slot++)if(!pending[slot]){active=slot;Arrays.fill(written,false);break;}
        }catch(RuntimeException failure){disabled=true;active=-1;}
    }
    void point(int i){if(active>=0&&!written[i]){glQueryCounter(queries[active*8+i],GL_TIMESTAMP);written[i]=true;}}
    void end(){if(active<0)return;for(int i=0;i<8;i++)point(i);pending[active]=true;issued[active]=frame;active=-1;}
    String summary(){
        String result=String.format(Locale.ROOT,"GPU samples=%d pyramidAvgMs=%.3f testAvgMs=%.3f commandsAvgMs=%.3f planesAvgMs=%.3f",samples,sum[0]/1e6/Math.max(1,samples),sum[1]/1e6/Math.max(1,samples),sum[2]/1e6/Math.max(1,samples),sum[3]/1e6/Math.max(1,samples));
        Arrays.fill(sum,0);samples=0;return result;
    }
    public void close(){active=-1;for(int q:queries)if(q!=0)glDeleteQueries(q);Arrays.fill(queries,0);Arrays.fill(pending,false);Arrays.fill(sum,0);samples=frame=0;disabled=false;}
}
