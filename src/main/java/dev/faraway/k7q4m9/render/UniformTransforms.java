package dev.faraway.k7q4m9.render;

import net.minecraft.client.renderer.DynamicGpuData;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** The engine retains the last Transform for equality checks; caller-owned mutable values must not escape. */
public final class UniformTransforms {
    private UniformTransforms() {}
    public static DynamicGpuData.Transform snapshot(Matrix4f model,Vector4f color,Vector3f offset,Matrix4f texture) {
        return new DynamicGpuData.Transform(new Matrix4f(model),new Vector4f(color),new Vector3f(offset),new Matrix4f(texture));
    }
}
