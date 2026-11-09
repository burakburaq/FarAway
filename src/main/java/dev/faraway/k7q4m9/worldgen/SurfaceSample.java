package dev.faraway.k7q4m9.worldgen;

import dev.faraway.k7q4m9.mesh.TextureMaterial;

/** One horizontal sample of the exposed terrain and water. */
public record SurfaceSample(short terrainTopY,short waterTopY,int biomeId,int color,int waterColor,
                            boolean terrain,boolean water,int material,int foliageColor) {
    public SurfaceSample(short terrainTopY,short waterTopY,int biomeId,int color,int waterColor,boolean terrain,boolean water) {
        this(terrainTopY,waterTopY,biomeId,color,waterColor,terrain,water,TextureMaterial.terrain(color),color);
    }
}
