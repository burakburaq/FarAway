package dev.faraway.k7q4m9.terrain;

public record TileKey(int x, int z, int spacing, int sizeSpacing) {
    public static final int CELLS = 32;
    public static final boolean WORLD = Boolean.getBoolean("faraway.worldGrid");
    public static final int SPACING = WORLD ? WorldGrid.SPACING : 16;
    public static final int BLOCKS = CELLS * SPACING;
    public TileKey(int x, int z) { this(x,z,SPACING); }
    public TileKey(int x,int z,int spacing) { this(x,z,spacing,spacing); }
    public TileKey {
        if (spacing < 1 || spacing > 262_144 || Integer.bitCount(spacing) != 1)
            throw new IllegalArgumentException("Invalid LOD spacing");
        if (sizeSpacing < 1 || sizeSpacing > 262_144 || Integer.bitCount(sizeSpacing) != 1)
            throw new IllegalArgumentException("Invalid tile size");
        if(spacing>(long)CELLS*sizeSpacing) throw new IllegalArgumentException("LOD spacing exceeds tile size");
    }
    public int blocks() { return Math.multiplyExact(CELLS,sizeSpacing); }
    public static TileKey containing(int blockX, int blockZ) {
        return new TileKey(Math.floorDiv(blockX, BLOCKS), Math.floorDiv(blockZ, BLOCKS));
    }
    public int originX() { return Math.toIntExact((long)x * blocks()); }
    public int originZ() { return Math.toIntExact((long)z * blocks()); }
    public TileKey child(int dx,int dz) {
        if (sizeSpacing==1 || dx<0 || dx>1 || dz<0 || dz>1) throw new IllegalArgumentException("Invalid child");
        return new TileKey(Math.addExact(Math.multiplyExact(x,2),dx),Math.addExact(Math.multiplyExact(z,2),dz),Math.max(1,spacing/2),sizeSpacing/2);
    }
}
