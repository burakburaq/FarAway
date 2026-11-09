package dev.faraway.k7q4m9.diagnostics;

import java.util.Arrays;

/** Bounded render-thread samples. Mean/max cover the whole window; percentiles cover retained samples. */
public final class TimingWindow {
    private final long[] samples;
    private int cursor,size;
    private long count,sum,max;
    public TimingWindow(int capacity) { if(capacity<1) throw new IllegalArgumentException("capacity"); samples=new long[capacity]; }
    public void add(long ns) { ns=Math.max(0,ns); samples[cursor]=ns; cursor=(cursor+1)%samples.length; size=Math.min(size+1,samples.length); count++; sum+=ns; max=Math.max(max,ns); }
    public Summary summary() {
        long[] sorted=Arrays.copyOf(samples,size); Arrays.sort(sorted);
        return new Summary(count,size,count==0?0:(double)sum/count,max,percentile(sorted,.5),percentile(sorted,.95),percentile(sorted,.99));
    }
    private static long percentile(long[] a,double p) { return a.length==0?0:a[(int)Math.ceil(a.length*p)-1]; }
    public void clear() { cursor=size=0; count=sum=max=0; }
    public record Summary(long count,int retained,double meanNs,long maxNs,long p50Ns,long p95Ns,long p99Ns) {
        @Override public String toString() { return milliseconds(); }
        public String milliseconds() { return String.format(java.util.Locale.ROOT,"n=%d retained=%d avg=%.3f p50=%.3f p95=%.3f p99=%.3f max=%.3fms",count,retained,meanNs/1e6,p50Ns/1e6,p95Ns/1e6,p99Ns/1e6,maxNs/1e6); }
    }
}
