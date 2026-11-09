#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
layout(std140) uniform FarAwayCoverage {
    ivec4 CoverageOrigin;
    uvec4 CoverageBits[128];
    uvec4 SeamBits[128];
};
layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 tilePosition;
layout(location = 2) in vec3 surfacePosition;
uniform sampler2D Sampler0;
uniform sampler2D Sampler2;
layout(std140) uniform FarAwayMaterials {
    vec4 MaterialRects[254];
    uvec4 MaterialVariants[254]; // Offset, count, total weight, face mask.
    uvec4 TextureVariants[256]; // Six packed UV transforms and cumulative weight.
};
layout(location = 0) out vec4 fragColor;
bool coverageContains(ivec2 chunk, bool seam) {
    ivec2 cell = chunk - CoverageOrigin.xy;
    if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, ivec2(128)))) return false;
    int index = cell.y * 128 + cell.x;
    int word = index >> 5;
    uint bits = seam ? SeamBits[word >> 2][word & 3] : CoverageBits[word >> 2][word & 3];
    return (bits & (1u << uint(index & 31))) != 0u;
}
vec2 transformUv(vec2 value, uint transform) {
    if ((transform & 4u) != 0u) value = value.yx;
    if ((transform & 1u) != 0u) value.x = -value.x;
    if ((transform & 2u) != 0u) value.y = -value.y;
    return value;
}
uint blockTransform(uvec4 variants, ivec3 block, uint face) {
    uint hash = uint(block.x) * 0x9e3779b9u ^ uint(block.z) * 0x85ebca6bu ^ uint(block.y) * 0xc2b2ae35u;
    hash ^= hash >> 16u;
    hash *= 0x7feb352du;
    hash ^= hash >> 15u;
    uint pick = hash % variants.z;
    if (variants.y == variants.z) {
        return (TextureVariants[variants.x + pick].x >> (face * 3u)) & 7u;
    }
    for (uint i = 0u; i < variants.y; ++i) {
        uvec4 variant = TextureVariants[variants.x + i];
        if (pick < variant.y) return (variant.x >> (face * 3u)) & 7u;
    }
    return 0u;
}
void main() {
    vec3 dx = dFdx(surfacePosition), dy = dFdy(surfacePosition);
    vec2 cameraDelta = tilePosition - TextureMat[3].xy;
    float radius = TextureMat[3].z;
    if (radius > 0.0 && dot(cameraDelta, cameraDelta) > radius * radius) discard;
    // ModelOffset carries the exact tile origin in chunks, not a floating world position.
    vec3 outward = cross(dx, dy);
    vec3 normal = abs(outward);
    vec2 chunkPosition = tilePosition / 16.0;
    ivec2 localChunk = ivec2(floor(chunkPosition));
    ivec2 adjacent = ivec2(0);
    bool xWall = normal.x >= normal.z;
    float wall = xWall ? normal.x : normal.z;
    if (wall > 0.0 && max(normal.y, xWall ? normal.z : normal.x) <= wall * 0.0001) {
        int axis = xWall ? 0 : 1;
        float edge = round(chunkPosition[axis]);
        if (abs(chunkPosition[axis] - edge) < 0.0001) {
            bool positive = ((xWall ? outward.x : outward.z) > 0.0) == gl_FrontFacing;
            localChunk[axis] = int(edge) - (positive ? 1 : 0);
            adjacent[axis] = positive ? 1 : -1;
        }
    }
    int material = int(round(vertexColor.a * 255.0)) - 1;
    if (TextureMat[0].y < 0.5) {
        ivec2 owner = ivec2(ModelOffset.xz) + localChunk;
        if (coverageContains(owner, false)) discard;
        // Only seed terrain walls are replaced. Decoration faces keep their own geometry.
        if (material >= 0 && material <= 4 && any(notEqual(adjacent, ivec2(0))) &&
            coverageContains(owner, true) && coverageContains(owner + adjacent, false)) discard;
    }
    bool opaqueLeaves = TextureMat[2].w > 0.5 && (material == 7 || (material >= 20 && material <= 27));
    if(TextureMat[2].y< -0.5) { fragColor=vec4(1.0,0.0,0.0,1.0); return; }
    if(TextureMat[2].y>1.5) { fragColor=vec4(vertexColor.rgb,1.0); return; }
    if(TextureMat[2].y>0.5) { int c=int(ModelOffset.y); fragColor=vec4(float((c>>16)&255),float((c>>8)&255),float(c&255),255.0)/255.0; return; }
    if (material == 80) { fragColor = vec4(1.0, 0.0, 1.0, 1.0); return; }
    vec3 base = vertexColor.rgb;
    if (material >= 0 && material < 254) {
        bool horizontal = normal.y >= max(normal.x, normal.z);
        bool xFace = normal.x >= normal.z;
        vec2 uv = horizontal ? surfacePosition.xz : xFace ? surfacePosition.zy : surfacePosition.xy;
        vec2 gradX = horizontal ? dx.xz : xFace ? dx.zy : dx.xy;
        vec2 gradY = horizontal ? dy.xz : xFace ? dy.zy : dy.xy;
        vec2 cellUv = fract(uv);
        if (TextureMat[0].x > 0.5) {
            uvec4 variants = MaterialVariants[material];
            int axis = horizontal ? 1 : xFace ? 0 : 2;
            bool positive = (outward[axis] > 0.0) == gl_FrontFacing;
            uint face = uint(axis * 2 + (positive ? 1 : 0));
            if ((variants.w & (1u << face)) != 0u) {
                ivec3 block = ivec3(floor(surfacePosition));
                // On an integer face plane, choose the block on the inside of the face.
                float plane = surfacePosition[axis];
                block[axis] = positive ? int(ceil(plane - 0.0001)) - 1 : int(floor(plane + 0.0001));
                block.xz += ivec2(ModelOffset.xz) * 16;
                uint transform = blockTransform(variants, block, face);
                cellUv = transformUv(cellUv - 0.5, transform) + 0.5;
                // Transform the continuous gradients to keep the mip footprint intact.
                gradX = transformUv(gradX, transform);
                gradY = transformUv(gradY, transform);
            }
        }
        vec4 rect = MaterialRects[material];
        vec2 size = rect.zw - rect.xy;
        vec4 texel = textureGrad(Sampler0, rect.xy + cellUv * size, gradX * size, gradY * size);
        // All leaf species use an opaque LOD surface, including atlas cutout pixels.
        if (!opaqueLeaves && texel.a < 0.1) discard;
        base *= texel.rgb;
    }
    vec3 skylight = texelFetch(Sampler2, ivec2(0, 15), 0).rgb;
    vec3 lit=base * skylight * ColorModulator.rgb;
    float fogRadius=TextureMat[2].x;
    float fog=fogRadius>0.0?smoothstep(fogRadius*0.8,fogRadius,length(cameraDelta)):0.0;
    fragColor = vec4(mix(lit,TextureMat[1].rgb,fog), 1.0);
}
