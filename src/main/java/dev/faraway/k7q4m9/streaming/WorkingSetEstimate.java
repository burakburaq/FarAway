package dev.faraway.k7q4m9.streaming;

/** Layout-derived preflight, not an FPS prediction or an assumed water distribution. */
public record WorkingSetEstimate(int requiredTiles,int prefetchedTiles,int retentionTiles,
                                 long requiredMinimumGpuBytes,long requiredTerrainGpuBytes,
                                 long retainedWorstCaseGpuBytes,boolean minimumFits) {
    public static final long VERTEX_BYTES=33L*33*16;
    public static final long TERRAIN_BYTES=VERTEX_BYTES+32L*32*6*2;
    public static final long MAX_TILE_BYTES=TERRAIN_BYTES+32L*32*12*16;
    public static WorkingSetEstimate plan(int x,int z,int radius,int maxTiles,long gpuBudget,long uniformBytes) {
        int required=new CoveragePlanner().update(x,z,radius,maxTiles).size();
        int prefetched=new CoveragePlanner().update(x,z,Math.addExact(radius,32),maxTiles).size();
        int retained=new CoveragePlanner().update(x,z,Math.addExact(radius,64),maxTiles).size();
        long minimum=Math.addExact(Math.multiplyExact(required,VERTEX_BYTES),uniformBytes);
        return new WorkingSetEstimate(required,prefetched,retained,minimum,
            Math.addExact(Math.multiplyExact(required,TERRAIN_BYTES),uniformBytes),
            Math.addExact(Math.multiplyExact(retained,MAX_TILE_BYTES),uniformBytes),minimum<=gpuBudget);
    }
}
