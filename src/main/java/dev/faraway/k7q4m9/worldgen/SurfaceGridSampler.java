package dev.faraway.k7q4m9.worldgen;

import java.util.function.BooleanSupplier;

/** Supplies globally aligned surface samples, including the caller's tile halo. */
public interface SurfaceGridSampler {
    default long seed() { return 0; }
    default dev.faraway.k7q4m9.terrain.VegetationField.Profile vegetationProfile(int biomeId) { return VegetationProfiles.EMPTY; }
    default SurfaceSample sample(int x,int z,int spacing) {
        return sampleRegion(x,z,1,1,spacing,()->false)[0];
    }
    default SurfaceSample vegetationSurface(int x,int z,int spacing) { return sample(x,z,spacing); }
    default SurfaceSample placementSurface(int x,int z) { return sample(x,z,1); }
    int minY();
    int maxY();
    int seaLevel();
    SurfaceSample[] sampleRegion(int originX,int originZ,int width,int depth,int spacing,BooleanSupplier cancelled);
}
