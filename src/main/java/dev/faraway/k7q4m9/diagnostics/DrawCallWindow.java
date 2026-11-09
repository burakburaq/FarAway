package dev.faraway.k7q4m9.diagnostics;
/** Exact trailing-window total, stored without per-frame allocations. Render thread only. */
public final class DrawCallWindow {
    private final long durationNs;
    public DrawCallWindow() { this(1_000_000_000L); }
    public DrawCallWindow(long durationNs) { if(durationNs<=0)throw new IllegalArgumentException("Invalid window duration");this.durationNs=durationNs; }
    private long[] times=new long[256],counts=new long[256];
    private int head,size;private long total;
    public void add(long now,long calls) {
        sum(now);if(calls<=0)return;
        if(size==times.length) {var t=new long[size*2];var c=new long[size*2];for(int i=0;i<size;i++){int at=(head+i)%size;t[i]=times[at];c[i]=counts[at];}times=t;counts=c;head=0;}
        int at=(head+size)%times.length;times[at]=now;counts[at]=calls;size++;total+=calls;
    }
    public long sum(long now) {while(size>0&&now-times[head]>=durationNs){total-=counts[head];head=(head+1)%times.length;size--;}return total;}
    public void clear(){head=size=0;total=0;}
}
