package dev.faraway.k7q4m9.streaming;

import dev.faraway.k7q4m9.terrain.TileKey;

/** Accumulates retained tiles without keeping historical samples. */
public final class LodTimingStats {
    private static final int LEVELS=19;
    private static final int SAMPLES_PER_SECTOR=16*16;
    private final int[] counts=new int[LEVELS];
    private final long[] horizontalSamples=new long[LEVELS];
    private final double[] totals=new double[LEVELS];
    private final double[] normalizedTotals=new double[LEVELS];
    public record Summary(int detail,int tileCount,double sectorCount,double averageMsPerSector,double cumulativeSeconds) {}
    public void record(TileKey key,long nanos) {
        long side=key.blocks()/key.spacing();
        record(key.spacing(),side*side,nanos);
    }
    public void record(int spacing,long sampleCount,long nanos) {
        if(spacing<1 || Integer.bitCount(spacing)!=1 || Integer.numberOfTrailingZeros(spacing)>=LEVELS || sampleCount<=0 || nanos<0)
            throw new IllegalArgumentException();
        int level=Integer.numberOfTrailingZeros(spacing);
        counts[level]++;
        horizontalSamples[level]+=sampleCount;
        totals[level]+=nanos;
        normalizedTotals[level]+=nanos*(double)SAMPLES_PER_SECTOR/sampleCount;
    }
    public Summary[] snapshot() {
        var result=new Summary[LEVELS]; double cumulative=0;
        for(int level=0;level<LEVELS;level++) {
            cumulative+=totals[level];
            int count=counts[level];
            double average=count==0?Double.NaN:normalizedTotals[level]/count/1_000_000.0;
            result[level]=new Summary(1<<level,count,horizontalSamples[level]/(double)SAMPLES_PER_SECTOR,average,cumulative/1_000_000_000.0);
        }
        return result;
    }
}
