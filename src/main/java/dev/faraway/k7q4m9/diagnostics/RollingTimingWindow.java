package dev.faraway.k7q4m9.diagnostics;
/** Arithmetic mean of samples observed in the configured trailing interval. Render thread only. */
public final class RollingTimingWindow {
    private final long durationNs;
    public RollingTimingWindow() { this(1_000_000_000L); }
    public RollingTimingWindow(long durationNs) { if(durationNs<=0)throw new IllegalArgumentException("Invalid window duration");this.durationNs=durationNs; }
    private long[] times=new long[256],values=new long[256];
    private int head,size;private long sum;
    public void add(long now,long value) {
        expire(now);if(value<0)return;
        if(size==times.length){var t=new long[size*2];var v=new long[size*2];for(int i=0;i<size;i++){int at=(head+i)%size;t[i]=times[at];v[i]=values[at];}times=t;values=v;head=0;}
        int at=(head+size)%times.length;times[at]=now;values[at]=value;size++;sum+=value;
    }
    private void expire(long now){while(size>0&&now-times[head]>=durationNs){sum-=values[head];head=(head+1)%times.length;size--;}}
    public double average(long now){expire(now);return size==0?Double.NaN:(double)sum/size;}
    public void clear(){head=size=0;sum=0;}
}
