package dev.faraway.k7q4m9.render;

public final class LodColors {
    private static final int[] RGB={0x0000FF,0x00FFCC,0x00FF00,0xFFFF00,0xFF8000,0xFF0000,0x0000FF,0x00FFCC,0x00FF00,0xFFFF00,0xFF8000,0xFF0000,0x0000FF,0x00FFCC,0x00FF00,0xFFFF00,0xFF8000,0xFF0000,0x0000FF};
    private LodColors() {}
    public static int forSpacing(int spacing) { return RGB[Integer.numberOfTrailingZeros(spacing)]; }
}
