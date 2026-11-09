package dev.faraway.k7q4m9.worldgen;

import java.util.LinkedHashMap;

/** Session-owned, bounded RAM cache of immutable surface samples. Noise state stays worker-local. */
public final class SurfaceSampleCache {
    private static final int SEGMENTS=32, CAPACITY_PER_SEGMENT=4096;
    private final Segment[] segments=new Segment[SEGMENTS];
    private final java.util.concurrent.atomic.LongAdder hits=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder misses=new java.util.concurrent.atomic.LongAdder();
    public SurfaceSampleCache() { for(int i=0;i<SEGMENTS;i++) segments[i]=new Segment(); }
    public static long key(int x,int z) { return ((long)x<<32) | (z & 0xffffffffL); }
    static int x(long key) { return (int)(key>>32); }
    static int z(long key) { return (int)key; }
    private Segment segment(long key) {
        long hash=key; hash^=hash>>>33; hash*=0xff51afd7ed558ccdL; hash^=hash>>>33;
        return segments[(int)hash & (SEGMENTS-1)];
    }
    public SurfaceSample get(long key) {
        var segment=segment(key);
        SurfaceSample sample;
        synchronized(segment) { var item=segment.values.get(key); sample=item==null?null:item.sample(); if(item!=null) item.used=System.nanoTime(); }
        if(sample==null) misses.increment(); else hits.increment();
        return sample;
    }
    public void put(long key,SurfaceSample sample) {
        var segment=segment(key);
        synchronized(segment) {
            segment.values.put(key,new Item(sample,System.nanoTime()));
            if(segment.values.size()>CAPACITY_PER_SEGMENT) {
                var iterator=segment.values.keySet().iterator(); iterator.next(); iterator.remove();
            }
        }
    }
    public String status() { return "sampleCacheHits="+hits.sum()+" sampleCacheMisses="+misses.sum(); }
    public void expire(long now) {
        for(var segment:segments) synchronized(segment) {
            var it=segment.values.values().iterator();
            while(it.hasNext()) { if(now-it.next().used()<1_000_000_000L) break; it.remove(); }
        }
    }
    private static final class Item {
        final SurfaceSample sample; long used;
        Item(SurfaceSample sample,long used) { this.sample=sample;this.used=used; }
        SurfaceSample sample(){return sample;}
        long used(){return used;}
    }
    private static final class Segment {
        final LinkedHashMap<Long,Item> values=new LinkedHashMap<>(256,.75f,true);
    }
}
