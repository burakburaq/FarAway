package dev.faraway.k7q4m9.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.BooleanSupplier;
import dev.faraway.k7q4m9.worldgen.SurfaceSample;

/** Immutable publication boundary. Mutable scratch samples never escape generation. */
public final class LodTile {
    public static final int VERTICES = 33;
    public static final int HALO = 35;
    private final TileKey key;
    private final int verticesPerSide,haloSide;
    private final short[] heights, water, biomes, haloHeights,haloWater;
    private final boolean[] haloWet;
    private final short[] materials;
    private final byte[] flags;
    private final Material[] palette;
    private final int minY, maxY;
    LodTile(TileKey key, SurfaceSample[] samples) {
        this.key = key;
        verticesPerSide=key.blocks()/key.spacing()+1; haloSide=verticesPerSide+2;
        int count = verticesPerSide * verticesPerSide;
        heights = new short[count]; water = new short[count]; biomes = new short[count];
        materials = new short[count]; flags = new byte[count]; haloHeights = new short[haloSide * haloSide];
        haloWater=new short[haloSide*haloSide]; haloWet=new boolean[haloSide*haloSide];
        var indices = new HashMap<Material, Integer>();
        var colors = new ArrayList<Material>();
        for (int i = 0; i < samples.length; i++) { haloHeights[i] = samples[i].terrainTopY(); haloWater[i]=samples[i].waterTopY(); haloWet[i]=samples[i].water(); }
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int z = 0; z < verticesPerSide; z++) for (int x = 0; x < verticesPerSide; x++) {
            int i = z * verticesPerSide + x;
            var sample = samples[(z + 1) * haloSide + x + 1];
            heights[i] = sample.terrainTopY(); water[i] = sample.waterTopY(); biomes[i] = (short)sample.biomeId();
            flags[i] = (byte)((sample.terrain() ? 1 : 0) | (sample.water() ? 2 : 0));
            Material color = new Material(sample.color(), sample.waterColor());
            Integer id = indices.get(color);
            if (id == null) {
                id = colors.size();
                if (id > 65535) throw new IllegalStateException("Material palette exceeds 65536 entries");
                colors.add(color); indices.put(color, id);
            }
            materials[i] = id.shortValue();
            if (sample.terrain()) { low = Math.min(low, heights[i]); high = Math.max(high, heights[i]); }
            if (sample.water()) high = Math.max(high, water[i]);
        }
        palette = colors.toArray(Material[]::new);
        minY = low == Integer.MAX_VALUE ? 0 : low;
        maxY = high == Integer.MIN_VALUE ? 0 : high;
    }
    public static LodTile generate(TileKey key, dev.faraway.k7q4m9.worldgen.SurfaceGridSampler sampler, BooleanSupplier cancelled) {
        int halo=key.blocks()/key.spacing()+3;
        var samples = sampler.sampleRegion(Math.subtractExact(key.originX(), key.spacing()), Math.subtractExact(key.originZ(), key.spacing()), halo, halo, key.spacing(), cancelled);
        return new LodTile(key, samples);
    }

    public int cells() { return verticesPerSide-1; }
    public int verticesPerSide() { return verticesPerSide; }
    public TileKey key() { return key; }
    public int height(int x, int z) { return heights[z * verticesPerSide + x]; }
    public int waterHeight(int x, int z) { return water[z * verticesPerSide + x]; }
    public int biome(int x, int z) { return Short.toUnsignedInt(biomes[z * verticesPerSide + x]); }
    public boolean terrain(int x, int z) { return (flags[z * verticesPerSide + x] & 1) != 0; }
    public boolean wet(int x, int z) { return (flags[z * verticesPerSide + x] & 2) != 0; }
    public Material material(int x, int z) { return palette[Short.toUnsignedInt(materials[z * verticesPerSide + x])]; }
    public int haloHeight(int x, int z) { return haloHeights[(z + 1) * haloSide + x + 1]; }
    public boolean haloWet(int x,int z) { return haloWet[(z+1)*haloSide+x+1]; }
    public int haloWaterHeight(int x,int z) { return haloWater[(z+1)*haloSide+x+1]; }
    public int minY() { return minY; }
    public int maxY() { return maxY; }
    public long payloadBytes() { return heights.length * 9L + haloHeights.length * 5L + palette.length * 8L; }
    public record Material(int terrainColor, int waterColor) {}
}
