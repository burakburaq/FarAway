#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
layout(location=0) in vec3 Origin;
layout(location=1) in float ExtentU;
layout(location=2) in float ExtentV;
layout(location=3) in uint Axes;
layout(location=4) in vec4 ColorA;
layout(location=5) in vec4 ColorB;
layout(location=6) in vec4 ColorC;
layout(location=7) in vec4 ColorD;
layout(location=0) out vec4 vertexColor;
layout(location=1) out vec2 tilePosition;
layout(location=2) out vec3 surfacePosition;
void main() {
    const int corners[6]=int[6](0,1,2,2,1,3);
    int corner=corners[gl_VertexIndex];
    vec3 position=Origin;
    if((corner&1)!=0)position[int(Axes&3u)]+=ExtentU;
    if((corner&2)!=0)position[int((Axes>>2u)&3u)]+=ExtentV;
    gl_Position=ProjMat*ModelViewMat*vec4(position,1.0);
    vertexColor=corner==0?ColorA:corner==1?ColorB:corner==2?ColorC:ColorD;
    tilePosition=position.xz;
    surfacePosition=position;
}
