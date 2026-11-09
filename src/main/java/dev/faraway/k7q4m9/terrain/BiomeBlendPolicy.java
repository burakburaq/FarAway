package dev.faraway.k7q4m9.terrain;

public final class BiomeBlendPolicy {
    private BiomeBlendPolicy() {}
    public static boolean enabled(int spacing,int maxLod) {
        if(spacing<1 || Integer.bitCount(spacing)!=1 || maxLod< -1 || maxLod>18) throw new IllegalArgumentException();
        return Integer.numberOfTrailingZeros(spacing)<=maxLod;
    }
}
