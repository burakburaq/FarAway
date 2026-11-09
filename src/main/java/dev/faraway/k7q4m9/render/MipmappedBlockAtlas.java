package dev.faraway.k7q4m9.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.faraway.k7q4m9.client.FarAwayClient;
import dev.faraway.k7q4m9.client.LodSettings;
import dev.faraway.k7q4m9.client.LodSettings.MipmapMode;
import dev.faraway.k7q4m9.client.MipmapOverride;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/** A separate block atlas is needed only while the shared one has too few levels. */
public final class MipmappedBlockAtlas implements AutoCloseable {
    public static final Identifier LOCATION=Identifier.fromNamespaceAndPath("faraway","textures/atlas/lod_blocks.png");

    private GpuTextureView sourceView;
    private TextureAtlas atlas;
    private CompletableFuture<SpriteLoader.Preparations> pending;

    void update() {
        var settings=LodSettings.get();
        if(MipmapOverride.effectiveMode(settings)!=MipmapMode.LOD_ONLY || !settings.renderTiles()) {
            close();
            return;
        }
        var client=Minecraft.getInstance();
        var shared=(TextureAtlas)client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        var view=shared.getTextureView();
        if(view.mipLevels()>MipmapOverride.LEVEL) {
            close();
            return;
        }
        if(view!=sourceView) {
            close();
            sourceView=view;
            // Use the vanilla source definitions so pack overrides, palette
            // permutations, model sprites and animated water remain available.
            pending=new SpriteLoader(LOCATION,shared.maxSupportedTextureSize())
                .loadAndStitch(client.getResourceManager(),AtlasIds.BLOCKS,MipmapOverride.LEVEL,
                    Util.backgroundExecutor().forName("FarAway LOD textures"),Set.of())
                .thenCompose(prepared -> prepared.readyForUpload().handle((ignored,failure) -> {
                    if(failure!=null) {
                        dispose(prepared);
                        throw new java.util.concurrent.CompletionException(failure);
                    }
                    return prepared;
                }));
        }
        if(pending==null || !pending.isDone()) return;
        try {
            var prepared=pending.join();
            if(prepared.mipLevel()!=MipmapOverride.LEVEL) {
                dispose(prepared);
                throw new IllegalStateException("LOD block atlas has only "+prepared.mipLevel()+" mip levels");
            }
            var replacement=new TextureAtlas(LOCATION);
            try {
                replacement.upload(prepared);
            } catch(RuntimeException | Error failure) {
                replacement.close();
                dispose(prepared);
                throw failure;
            }
            atlas=replacement;
        } catch(RuntimeException failure) {
            FarAwayClient.LOGGER.error("FarAway could not build its mipmap atlas; using the shared block atlas",failure);
        } finally {
            pending=null;
        }
    }

    TextureAtlas select(TextureAtlas shared) {
        return atlas!=null && shared.getTextureView()==sourceView && MipmapOverride.effectiveMode(LodSettings.get())==MipmapMode.LOD_ONLY
            ?atlas:shared;
    }

    void tick() {
        if(atlas!=null && MipmapOverride.effectiveMode(LodSettings.get())==MipmapMode.LOD_ONLY) atlas.tick();
    }

    @Override public void close() {
        if(pending!=null) {
            pending.thenAccept(MipmappedBlockAtlas::dispose);
            pending=null;
        }
        if(atlas!=null) {
            var retired=atlas;
            atlas=null;
            RenderSystem.queueFencedTask(retired::close);
        }
        sourceView=null;
    }

    private static void dispose(SpriteLoader.Preparations prepared) {
        prepared.regions().values().forEach(TextureAtlasSprite::close);
    }
}
