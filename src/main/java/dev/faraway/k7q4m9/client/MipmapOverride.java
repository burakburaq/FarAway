package dev.faraway.k7q4m9.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import dev.faraway.k7q4m9.client.LodSettings.MipmapMode;

/** Coordinates the optional global mipmap override with atlas reloads. */
public final class MipmapOverride {
    public static final int LEVEL=4;
    private static volatile boolean resizedGlobalSprites;

    private MipmapOverride() {}

    public static MipmapMode effectiveMode(LodSettings.Values settings) {
        var requested=settings.mipmapMode();
        return requested==MipmapMode.GLOBAL && !settings.lockedOptions()?MipmapMode.LOD_ONLY:requested;
    }

    public static boolean global() { return effectiveMode(LodSettings.get())==MipmapMode.GLOBAL; }
    public static int effective(int requested) { return global()?LEVEL:requested; }

    public static void recordGlobalSpriteResize(boolean resized) { resizedGlobalSprites=resized; }

    public static void settingsApplied(LodSettings.Values previous) {
        if((effectiveMode(previous)==MipmapMode.GLOBAL)==global()) return;
        var client=Minecraft.getInstance();
        int mip=client.options.mipmapLevels().get();
        var atlas=(TextureAtlas)client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        client.updateMaxMipLevel(mip);
        if(atlas.getTextureView().mipLevels()!=mip+1 || !global() && resizedGlobalSprites) {
            client.delayTextureReload().exceptionally(failure -> {
                FarAwayClient.LOGGER.error("FarAway mipmap atlas reload failed",failure);
                return null;
            });
        }
    }
}
