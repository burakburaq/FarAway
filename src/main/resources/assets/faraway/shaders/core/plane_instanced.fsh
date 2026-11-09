#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
layout(std140) uniform FarAwayCoverage { ivec4 CoverageOrigin; uvec4 CoverageBits[128]; };
layout(location = 0) in vec2 tilePosition;
layout(location = 1) flat in vec4 instanceAtlas;
layout(location = 2) flat in vec4 instanceInfo;
layout(location = 3) flat in vec2 instanceOffset;
uniform sampler2D Sampler0;
uniform sampler2D Sampler2;
layout(location = 0) out vec4 fragColor;
bool vanillaReady(ivec2 chunk) {
    ivec2 cell=chunk-CoverageOrigin.xy;
    if(any(lessThan(cell,ivec2(0))) || any(greaterThanEqual(cell,ivec2(128)))) return false;
    int index=cell.y*128+cell.x,word=index>>5;
    return (CoverageBits[word>>2][word&3] & (1u<<uint(index&31)))!=0u;
}
void main() {
    vec2 cameraDelta=tilePosition+instanceOffset;
    float radius=TextureMat[3].z;
    if(radius>0.0 && dot(cameraDelta,cameraDelta)>radius*radius) discard;
    ivec2 chunk=ivec2(instanceInfo.xz)+ivec2(floor(tilePosition/16.0));
    if (vanillaReady(chunk)) discard;
    ivec2 cell=ivec2(clamp(floor(tilePosition/instanceAtlas.w),vec2(0),vec2(instanceAtlas.z-1.0)));
    vec4 surface=texelFetch(Sampler0,ivec2(instanceAtlas.xy)+cell,0);
    if(surface.a<0.5) discard;
    if(TextureMat[2].y< -0.5) { fragColor=vec4(1.0,0.0,0.0,1.0); return; }
    if(TextureMat[2].y>0.5) { int c=int(instanceInfo.y); fragColor=vec4(float((c>>16)&255),float((c>>8)&255),float(c&255),255.0)/255.0; return; }
    vec3 base=surface.rgb;
    vec3 skylight=texelFetch(Sampler2,ivec2(0,15),0).rgb;
    float fogRadius=TextureMat[2].x;
    float fog=fogRadius>0.0?smoothstep(fogRadius*0.8,fogRadius,length(cameraDelta)):0.0;
    fragColor=vec4(mix(base*skylight*ColorModulator.rgb,TextureMat[1].rgb,fog),1.0);
}
