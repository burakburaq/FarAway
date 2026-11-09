package dev.faraway.k7q4m9.mesh;

import dev.faraway.k7q4m9.terrain.TileKey;
import dev.faraway.k7q4m9.terrain.VoxelTile;

/** Compact sampled surface for matching voxel terrain to loaded vanilla chunks. */
public final class VoxelSurface {
    private final TileKey key;
    private final int side;
    private final short[] heights;
    private final int[] appearances;

    VoxelSurface(VoxelTile tile) {
        key=tile.key();
        side=tile.cells();
        heights=new short[side*side];
        appearances=new int[heights.length];
        for(int z=0;z<side;z++) for(int x=0;x<side;x++) {
            int index=z*side+x;
            boolean wet=tile.wet(x,z);
            heights[index]=(short)(wet?tile.waterSurface(x,z):tile.terrainSurface(x,z));
            int material=wet?0:tile.material(x,z)+1;
            int rgb=wet?tile.waterColor(x,z):material==1?tile.surfaceColor(x,z):0xffffff;
            appearances[index]=(material<<24)|(rgb&0xffffff);
        }
    }

    private int index(int x,int z) {
        return (z-key.originZ())/key.spacing()*side+(x-key.originX())/key.spacing();
    }
    public int height(int x,int z) { return heights[index(x,z)]; }
    public int appearance(int x,int z) { return appearances[index(x,z)]; }
    public long bytes() { return heights.length*6L; }
}
