package dev.faraway.k7q4m9.streaming;

import dev.faraway.k7q4m9.terrain.TileKey;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Fixed-spacing M1 comparison coverage. Whole-world LOD uses a hierarchy, not this enumerator. */
public final class CoveragePlanner {
    private int lastChunkX=Integer.MIN_VALUE, lastChunkZ=Integer.MIN_VALUE, lastRadius=-1, lastLimit=-1;
    private Set<TileKey> cached=Set.of();
    public Set<TileKey> update(int cameraX,int cameraZ,int radiusChunks,int maxTiles) {
        if (radiusChunks < 1 || maxTiles < 1) throw new IllegalArgumentException("Invalid coverage budget");
        if (TileKey.WORLD) {
            if (!cached.isEmpty()) return cached;
            var grid = new LinkedHashSet<TileKey>();
            for (int z=dev.faraway.k7q4m9.terrain.WorldGrid.MIN_TILE; z<=dev.faraway.k7q4m9.terrain.WorldGrid.MAX_TILE; z++)
                for (int x=dev.faraway.k7q4m9.terrain.WorldGrid.MIN_TILE; x<=dev.faraway.k7q4m9.terrain.WorldGrid.MAX_TILE; x++) grid.add(new TileKey(x,z));
            if (grid.size()>maxTiles) throw new IllegalArgumentException("World grid exceeds tile budget");
            cached=Collections.unmodifiableSet(grid);
            return cached;
        }
        int chunkX=Math.floorDiv(cameraX,16), chunkZ=Math.floorDiv(cameraZ,16);
        if (chunkX==lastChunkX && chunkZ==lastChunkZ && radiusChunks==lastRadius && maxTiles==lastLimit) return cached;
        long cx=(long)chunkX*16+8, cz=(long)chunkZ*16+8;
        // One chunk of conservative padding covers all camera positions in the cached chunk.
        long radius=(long)radiusChunks*16+16;
        long minX=Math.max(-30_000_000,cx-radius), maxX=Math.min(29_999_999,cx+radius);
        long minZ=Math.max(-30_000_000,cz-radius), maxZ=Math.min(29_999_999,cz+radius);
        int tx0=(int)Math.floorDiv(minX,512), tx1=(int)Math.floorDiv(maxX,512);
        int tz0=(int)Math.floorDiv(minZ,512), tz1=(int)Math.floorDiv(maxZ,512);
        long boxCount=(long)(tx1-tx0+1)*(tz1-tz0+1);
        if (boxCount > (long)maxTiles*2) throw new IllegalArgumentException("Fixed-spacing working set exceeds tile budget");
        Set<TileKey> result=new LinkedHashSet<>();
        for (int z=tz0;z<=tz1;z++) for(int x=tx0;x<=tx1;x++) {
            long x0=(long)x*512,z0=(long)z*512;
            double dx=Math.max(0,Math.max(x0-cx,cx-(x0+512)));
            double dz=Math.max(0,Math.max(z0-cz,cz-(z0+512)));
            if (dx*dx+dz*dz <= (double)radius*radius) {
                result.add(new TileKey(x,z));
                if(result.size()>maxTiles) throw new IllegalArgumentException("Fixed-spacing working set exceeds tile budget");
            }
        }
        cached=Collections.unmodifiableSet(result);
        lastChunkX=chunkX; lastChunkZ=chunkZ; lastRadius=radiusChunks; lastLimit=maxTiles;
        return cached;
    }
}
