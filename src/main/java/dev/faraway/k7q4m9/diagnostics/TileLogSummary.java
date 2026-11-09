package dev.faraway.k7q4m9.diagnostics;

import dev.faraway.k7q4m9.client.FarAwayClient;

/** Bounded, thread-safe ten-second per-LOD tile statistics. */
public final class TileLogSummary {
    private static final int LEVELS=32, FIELDS=22;
    private static final long[][] totals=new long[LEVELS][FIELDS];
    private static final long[] maxTotal=new long[LEVELS];
    private static final Slowest[] slowest=new Slowest[LEVELS];
    public record Slowest(String key,double totalMs,double surfaceMs,double terrainMs,double decorationMs,double optimizeMs,double indexMs,double patchGenerateMs,double patchWaitMs,double patchHitMs,double filterMs,double structureMeshMs,double vegetationMs,double structureAppendMs) {}
    private TileLogSummary() {}
    public static synchronized void generated(dev.faraway.k7q4m9.terrain.TileKey key,long[] phases,int lod,long total,long surface,long terrain,long decoration,long optimize,long beforeBytes,long afterBytes,long beforeVertices,long vertices,long triangles,long indexBytes) {
        var a=totals[Math.min(LEVELS-1,Math.max(0,lod))]; a[0]++; a[1]+=total; a[2]+=surface; a[3]+=terrain; a[4]+=decoration; a[5]+=optimize; a[6]+=beforeBytes; a[7]+=afterBytes; a[8]+=beforeVertices; a[9]+=vertices; a[10]+=triangles; a[11]+=indexBytes; int level=Math.min(LEVELS-1,Math.max(0,lod)); for(int i=0;i<8;i++)a[14+i]+=phases[i];
        if(total>=maxTotal[level]) {
            maxTotal[level]=total;
            slowest[level]=new Slowest(key.toString(),total/1e6,surface/1e6,terrain/1e6,decoration/1e6,optimize/1e6,phases[0]/1e6,phases[1]/1e6,phases[2]/1e6,phases[3]/1e6,phases[4]/1e6,phases[5]/1e6,phases[6]/1e6,phases[7]/1e6);
        }
    }
    public static synchronized void uploaded(int lod,long bytes) { var a=totals[Math.min(LEVELS-1,Math.max(0,lod))]; a[12]++; a[13]+=bytes; }
    public static void flush() {
        long[][] snapshot=new long[LEVELS][]; long[] maxima; Slowest[] worst;
        synchronized(TileLogSummary.class) { for(int i=0;i<LEVELS;i++) { snapshot[i]=totals[i].clone(); java.util.Arrays.fill(totals[i],0); } worst=slowest.clone();java.util.Arrays.fill(slowest,null);maxima=maxTotal.clone(); java.util.Arrays.fill(maxTotal,0); }
        for(int i=0;i<LEVELS;i++) { var a=snapshot[i]; if(a[0]==0 && a[12]==0) continue; double n=Math.max(1,a[0]);
            FarAwayClient.LOGGER.info("FarAway tile summary lod={} generated={} totalAvgMs={} totalMaxMs={} surfaceAvgMs={} terrainAvgMs={} structuresAndOptimizeAvgMs={} optimizeAvgMs={} beforeBytes={} meshBytes={} beforeVertices={} vertices={} triangles={} indexBytes={} uploaded={} uploadBytes={}",i,a[0],a[1]/n/1e6,maxima[i]/1e6,a[2]/n/1e6,a[3]/n/1e6,a[4]/n/1e6,a[5]/n/1e6,a[6],a[7],a[8],a[9],a[10],a[11],a[12],a[13]);
            if(a[0]>0) FarAwayClient.LOGGER.info("FarAway tile phases lod={} indexAvgMs={} patchGenerateAvgMs={} patchWaitAvgMs={} patchHitAvgMs={} filterAvgMs={} structureMeshAvgMs={} vegetationAvgMs={} structureAppendAvgMs={} slowest={}",i,a[14]/n/1e6,a[15]/n/1e6,a[16]/n/1e6,a[17]/n/1e6,a[18]/n/1e6,a[19]/n/1e6,a[20]/n/1e6,a[21]/n/1e6,worst[i]);
        }
    }
}

