#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
layout(location = 0) in uint PackedPosition;
layout(location = 1) in vec4 Color;
layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 tilePosition;
layout(location = 2) out vec3 surfacePosition;
void main() {
    int y = int((PackedPosition >> 12u) & 65535u);
    if (y >= 32768) y -= 65536;
    vec3 position = vec3(float(PackedPosition & 63u) * TextureMat[2].z,
        float(y) / 16.0, float((PackedPosition >> 6u) & 63u) * TextureMat[2].z);
    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
    vertexColor = Color;
    tilePosition = position.xz;
    surfacePosition = position;
}
