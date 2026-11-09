package dev.faraway.k7q4m9.terrain;

/** Fixed coarse grid covering the complete vanilla world border. */
public final class WorldGrid {
    public static final int SPACING = 262_144;
    public static final int CELLS_PER_TILE = 32;
    public static final int TILE_BLOCKS = SPACING * CELLS_PER_TILE;
    public static final int WORLD_MIN = -30_000_000;
    public static final int WORLD_MAX = 30_000_000;
    public static final int MIN_TILE = Math.floorDiv(WORLD_MIN, TILE_BLOCKS);
    public static final int MAX_TILE = Math.floorDiv(WORLD_MAX - 1, TILE_BLOCKS);

    private WorldGrid() {}

    public static int origin(int tile) {
        if (tile < MIN_TILE || tile > MAX_TILE) throw new IllegalArgumentException("Tile outside world grid");
        return Math.multiplyExact(tile, TILE_BLOCKS);
    }

    public static int tileCount() {
        int width = MAX_TILE - MIN_TILE + 1;
        return Math.multiplyExact(width, width);
    }
}
