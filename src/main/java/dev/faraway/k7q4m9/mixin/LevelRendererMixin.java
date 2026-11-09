package dev.faraway.k7q4m9.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.faraway.k7q4m9.client.FarAwayClient;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native 26.3 backend. Iris integration remains a separate adapter milestone. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow @Final private LevelRenderState levelRenderState;
    @Inject(method="render",at=@At("HEAD"))
    private void farawayPrepare(CallbackInfo ci) {
        dev.faraway.k7q4m9.diagnostics.AdvancedRenderTiming.begin(dev.faraway.k7q4m9.client.LodSettings.get().advancedHud());
        dev.faraway.k7q4m9.diagnostics.RenderProfile.INSTANCE.begin();
        FarAwayClient.prepareFrame();
    }
    @Inject(method="render",at=@At("RETURN"))
    private void farawayProfileEnd(CallbackInfo ci) { FarAwayClient.renderPreviewOverlay(net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget(),levelRenderState.cameraRenderState); dev.faraway.k7q4m9.diagnostics.RenderProfile.INSTANCE.end(); dev.faraway.k7q4m9.diagnostics.AdvancedRenderTiming.end(); }

    @ModifyVariable(method="render",at=@At("HEAD"),argsOnly=true)
    private org.joml.Vector4f farawayWhiteBackground(org.joml.Vector4f color) {
        return dev.faraway.k7q4m9.client.LodSettings.get().colorByLod()?new org.joml.Vector4f(1,1,1,1):color;
    }
    @Inject(method="addSkyPass",at=@At("HEAD"),cancellable=true)
    private void farawayHideSky(com.mojang.blaze3d.framegraph.FrameGraphBuilder frame,CameraRenderState camera,
                                com.mojang.renderpearl.api.buffers.GpuBufferSlice fog,CallbackInfo ci) {
        if(dev.faraway.k7q4m9.client.LodSettings.get().colorByLod()) ci.cancel();
    }

    @Inject(method="executeSolid",at=@At("TAIL"))
    private void farawayOpaque(ChunkSectionsToRender sections,FeatureRenderDispatcher.PreparedFrame features,
                               RenderPass pass,CallbackInfo ci) {
        if(!dev.faraway.k7q4m9.client.LodSettings.hiZActive())FarAwayClient.renderOpaque(pass,levelRenderState.cameraRenderState);
    }
    @org.spongepowered.asm.mixin.gen.Invoker("executeClassicTransparency")
    protected abstract void farawayTransparency(ChunkSectionsToRender sections,FeatureRenderDispatcher.PreparedFrame features,RenderPass pass);

    // With Hi-Z, keep transparent geometry after both the occluder pass and the final LOD pass.
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method="lambda$addMainPass$0",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void farawayDeferTransparency(LevelRenderer owner,ChunkSectionsToRender sections,FeatureRenderDispatcher.PreparedFrame features,RenderPass pass,com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        if(!dev.faraway.k7q4m9.client.LodSettings.hiZActive())original.call(owner,sections,features,pass);
    }
    @Inject(method="lambda$addMainPass$0",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/commands/RenderPass;close()V",ordinal=0,shift=At.Shift.AFTER))
    private void farawayHiZ(com.mojang.renderpearl.api.buffers.GpuBufferSlice fog,boolean oit,ChunkSectionsToRender sections,FeatureRenderDispatcher.PreparedFrame features,boolean blockOutline,boolean outline,CallbackInfo ci,@com.llamalad7.mixinextras.sugar.Local com.mojang.blaze3d.pipeline.RenderTarget target) {
        FarAwayClient.renderDeferred(target,levelRenderState.cameraRenderState);
        if(!oit&&dev.faraway.k7q4m9.client.LodSettings.hiZActive()) {
            var encoder=com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
            try(var pass=encoder.createRenderPass(()->"FarAway post-Hi-Z transparency",target.getColorTextureView(),java.util.Optional.empty(),target.getDepthTextureView(),java.util.OptionalDouble.empty())) {
                com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(pass);farawayTransparency(sections,features,pass);
            }
            encoder.submit();
        }
    }
}
