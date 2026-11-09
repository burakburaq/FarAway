package dev.faraway.k7q4m9.mesh;

import dev.faraway.k7q4m9.mixin.WeightedVariantsAccess;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;

/** UV transforms taken from Minecraft's baked, weighted blockstate variants. */
final class TextureVariantRules {
    static final Direction[] FACES = {Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};
    static final int VARIANT_CAPACITY = 256;
    private static final float EPSILON = 0.0001f;

    record Choice(int transforms, int endWeight) {}

    record Rule(List<Choice> choices, int faceMask) {
        static final Rule NONE = new Rule(List.of(), 0);

        Rule forSprite(Identifier sprite, BakedQuad[] faces) {
            int mask = faceMask;
            for (int face = 0; face < faces.length; face++) {
                if (faces[face] == null || !sprite.equals(spriteName(faces[face]))) mask &= ~(1 << face);
            }
            return mask == 0 ? NONE : new Rule(choices, mask);
        }
    }

    private record Face(Identifier sprite, float plane, int transform) {}

    private TextureVariantRules() {}

    static Rule fromModel(BlockStateModel model, BakedQuad[] reference) {
        if (model instanceof WeightedVariantsAccess access) {
            return fromVariants(access.faraway$getVariants().unwrap(), reference);
        }
        return Rule.NONE;
    }

    static Rule fromVariants(List<Weighted<BlockStateModel>> variants, BakedQuad[] reference) {
        if (variants.size() < 2 || variants.size() > VARIANT_CAPACITY) return Rule.NONE;
        var referenceFaces = new Face[FACES.length];
        int eligibleFaces = 0;
        for (int face = 0; face < FACES.length; face++) {
            referenceFaces[face] = describe(reference[face]);
            if (referenceFaces[face] != null) eligibleFaces |= 1 << face;
        }

        var choices = new ArrayList<Choice>(variants.size());
        int endWeight = 0;
        int differences = 0;
        for (var variant : variants) {
            if (variant.weight() <= 0) continue;
            var quads = quads(variant.value());
            int transforms = 0;
            for (int face = 0; face < FACES.length; face++) {
                if ((eligibleFaces & (1 << face)) == 0) continue;
                Face base = referenceFaces[face];
                Face alternative = describe(quads[face]);
                if (alternative == null || !base.sprite().equals(alternative.sprite())
                    || Math.abs(base.plane() - alternative.plane()) > EPSILON) {
                    eligibleFaces &= ~(1 << face);
                    continue;
                }
                transforms |= alternative.transform() << (face * 3);
            }
            if (!choices.isEmpty()) differences |= choices.getFirst().transforms() ^ transforms;
            endWeight = Math.addExact(endWeight, variant.weight());
            choices.add(new Choice(transforms, endWeight));
        }

        for (int face = 0; face < FACES.length; face++) {
            if (((differences >>> (face * 3)) & 7) == 0) eligibleFaces &= ~(1 << face);
        }
        return eligibleFaces == 0 ? Rule.NONE : new Rule(List.copyOf(choices), eligibleFaces);
    }

    static BakedQuad[] quads(BlockStateModel model) {
        var parts = new ArrayList<BlockStateModelPart>();
        model.collectParts(RandomSource.create(0), parts);
        var faces = new BakedQuad[FACES.length];
        for (int face = 0; face < FACES.length; face++) {
            Direction direction = FACES[face];
            for (var part : parts) {
                var quads = part.getQuads(direction);
                if (quads.isEmpty()) quads = part.getQuads(null).stream().filter(q -> q.direction() == direction).toList();
                if (quads.isEmpty()) continue;
                var quad = quads.getFirst();
                if (quad.materialInfo().sprite().atlasLocation().equals(TextureAtlas.LOCATION_BLOCKS)) {
                    faces[face] = quad;
                    break;
                }
            }
        }
        return faces;
    }

    static Identifier spriteName(BakedQuad quad) {
        return quad.materialInfo().sprite().contents().name();
    }

    /** Match the quad's UVs against the eight symmetries of a square. */
    private static Face describe(BakedQuad quad) {
        if (quad == null) return null;
        var sprite = quad.materialInfo().sprite();
        float width = sprite.getU1() - sprite.getU0();
        float height = sprite.getV1() - sprite.getV0();
        if (width <= 0 || height <= 0) return null;
        var axis = quad.direction().getAxis();
        float plane = quad.position0().get(axis.ordinal());
        int candidates = 0xff;
        int corners = 0;
        for (int vertex = 0; vertex < 4; vertex++) {
            var position = quad.position(vertex);
            if (Math.abs(position.get(axis.ordinal()) - plane) > EPSILON) return null;
            int x = edge(axis == Direction.Axis.X ? position.z() : position.x());
            int y = edge(axis == Direction.Axis.Y ? position.z() : position.y());
            long uv = quad.packedUV(vertex);
            int u = edge((UVPair.unpackU(uv) - sprite.getU0()) / width);
            int v = edge((UVPair.unpackV(uv) - sprite.getV0()) / height);
            if (x < 0 || y < 0 || u < 0 || v < 0) return null;
            corners |= 1 << (x + y * 2);
            for (int transform = 0; transform < 8; transform++) {
                boolean swap = (transform & 4) != 0;
                int mappedU = (swap ? y : x) ^ (transform & 1);
                int mappedV = (swap ? x : y) ^ ((transform >>> 1) & 1);
                if (mappedU != u || mappedV != v) candidates &= ~(1 << transform);
            }
        }
        if (corners != 15 || candidates == 0) return null;
        return new Face(sprite.contents().name(), plane, Integer.numberOfTrailingZeros(candidates));
    }

    private static int edge(float value) {
        if (Math.abs(value) <= EPSILON) return 0;
        if (Math.abs(value - 1) <= EPSILON) return 1;
        return -1;
    }
}
