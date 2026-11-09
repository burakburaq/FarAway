package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.render.FarAwayView;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @org.spongepowered.asm.mixin.Shadow protected abstract void setPosition(net.minecraft.world.phys.Vec3 position);
    @org.spongepowered.asm.mixin.Shadow protected abstract void setRotation(float yaw,float pitch);
    @org.spongepowered.asm.mixin.Shadow private boolean detached;
    @Unique private final Projection farawayProjection=new Projection();
    @Inject(method="update",at=@At(value="INVOKE",target="Lnet/minecraft/client/Camera;prepareCullFrustum(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4f;Lnet/minecraft/world/phys/Vec3;)V"))
    private void farawayFreeCamera(DeltaTracker delta,CallbackInfo ci) {
        if(dev.faraway.k7q4m9.client.Freecam.active()) {
            dev.faraway.k7q4m9.client.Freecam.frame();
            setPosition(dev.faraway.k7q4m9.client.Freecam.position());
            setRotation(dev.faraway.k7q4m9.client.Freecam.yaw(),dev.faraway.k7q4m9.client.Freecam.pitch());
            detached=true;
        }
    }
    @Inject(method="extractRenderState",at=@At("TAIL"))
    private void farawayRange(CameraRenderState state,DeltaTracker delta,CallbackInfo ci) {
        int distance=FarAwayView.distanceBlocks;
        var shape=dev.faraway.k7q4m9.client.Freecam.frustum();
        if(shape!=null) {
            double reach=0;
            for(var point:dev.faraway.k7q4m9.client.Freecam.frustumGeometry().wire()) reach=Math.max(reach,shape.origin().add(point).distanceTo(state.pos));
            distance=(int)Math.min(1_000_000_000,Math.max(distance,Math.ceil(reach+1024)));
        }
        if(distance<=0) return;
        var window=Minecraft.getInstance().getWindow();
        state.depthFar=Math.max(state.depthFar,distance+1024f);
        farawayProjection.setupPerspective(.05f,state.depthFar,((Camera)(Object)this).getFov(),window.getWidth(),window.getHeight());
        farawayProjection.getMatrix(state.projectionMatrix);
        if (dev.faraway.k7q4m9.terrain.TileKey.WORLD) return;
        // Vanilla culls with forward depth, even though Projection reverses near/far
        // for drawing. Feeding the reverse-depth matrix to Frustum reverses its view
        // vector and can make offsetToFullyIncludeCameraCube loop forever.
        float cullFov=Math.max(((Camera)(Object)this).getFov(),Minecraft.getInstance().options.fov().get());
        var cullProjection=new Matrix4f().perspective((float)Math.toRadians(cullFov),
            (float)window.getWidth()/window.getHeight(),.05f,state.depthFar,
            RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
        var frustum=new Frustum(state.viewRotationMatrix,cullProjection);
        frustum.prepare(state.pos.x,state.pos.y,state.pos.z);
        state.cullFrustum.set(frustum);
    }
}
