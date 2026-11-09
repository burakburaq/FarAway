package dev.faraway.k7q4m9.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FarAwayClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("FarAway");

    private static DiagnosticsCommands diagnostics;
    public static void prepareFrame() {
        long start=System.nanoTime();
        dev.faraway.k7q4m9.worldgen.GpuSurfaceSampler.pump();
        dev.faraway.k7q4m9.diagnostics.RenderProfile.INSTANCE.add(dev.faraway.k7q4m9.diagnostics.RenderProfile.Stage.GPU_PUMP,System.nanoTime()-start);
        if(diagnostics!=null) diagnostics.prepareFrame();
    }
    public static void tickBlockTextures() { if(diagnostics!=null) diagnostics.tickBlockTextures(); }
    public static boolean benchmarkLocked() { return diagnostics!=null && diagnostics.benchmarkLocked(); }
    public static void renderPreviewOverlay(com.mojang.blaze3d.pipeline.RenderTarget target,net.minecraft.client.renderer.state.level.CameraRenderState camera) { if(diagnostics!=null)diagnostics.renderPreviewOverlay(target,camera); }
    public static void renderDeferred(com.mojang.blaze3d.pipeline.RenderTarget target,net.minecraft.client.renderer.state.level.CameraRenderState camera) { if(diagnostics!=null)diagnostics.renderDeferred(target,camera); }
    public static void renderOpaque(com.mojang.renderpearl.api.commands.RenderPass pass,net.minecraft.client.renderer.state.level.CameraRenderState camera) {
        if(diagnostics!=null) diagnostics.renderOpaque(pass,camera);
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("Build identity: {}",dev.faraway.k7q4m9.diagnostics.BuildIdentity.CURRENT);
        diagnostics=new DiagnosticsCommands();
        diagnostics.register();
        dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.setEnabled(LodSettings.get().worldgenProfiling());
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(
            client -> dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.stop());
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Freecam.tick();
            dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.poll();
        });
        LOGGER.info("FarAway initialized for Minecraft 26.3; terrain development in progress.");
    }
}
