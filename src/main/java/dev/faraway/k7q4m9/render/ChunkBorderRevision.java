package dev.faraway.k7q4m9.render;

/** Changes whenever the client replaces chunk data or changes a block on its outer face. */
public interface ChunkBorderRevision {
    long faraway$borderRevision();
}
