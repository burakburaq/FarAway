#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
struct PlaneInstance { vec4 offsetSize; vec4 atlas; vec4 info; };
layout(std140) uniform FarAwayPlaneInstances { PlaneInstance Instances[64]; };
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 0) out vec2 tilePosition;
layout(location = 1) flat out vec4 instanceAtlas;
layout(location = 2) flat out vec4 instanceInfo;
layout(location = 3) flat out vec2 instanceOffset;
void main() {
    PlaneInstance p=Instances[gl_InstanceIndex];
    vec3 relative=p.offsetSize.xyz+vec3(Position.x*p.offsetSize.w,0.0,Position.z*p.offsetSize.w);
    gl_Position=ProjMat*ModelViewMat*vec4(relative,1.0);
    tilePosition=Position.xz*p.offsetSize.w;
    instanceAtlas=p.atlas;
    instanceInfo=p.info;
    instanceOffset=p.offsetSize.xz;
}

