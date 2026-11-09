package dev.faraway.k7q4m9.render;

/** Applied only to fresh sprites that are about to be stitched into a forced atlas. */
public interface MipmapSpriteContents {
    boolean faraway$alignFramesForMipmaps(int level);
}
