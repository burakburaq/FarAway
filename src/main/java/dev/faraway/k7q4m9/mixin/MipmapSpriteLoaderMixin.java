package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.client.FarAwayClient;
import dev.faraway.k7q4m9.client.MipmapOverride;
import dev.faraway.k7q4m9.render.MipmappedBlockAtlas;
import java.util.List;
import java.util.concurrent.Executor;
import dev.faraway.k7q4m9.render.MipmapSpriteContents;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpriteLoader.class)
abstract class MipmapSpriteLoaderMixin {
    @Shadow @Final private Identifier location;

    @Inject(method="stitch",at=@At("HEAD"))
    private void faraway$prepareForcedMipmaps(List<SpriteContents> sprites,int mipLevel,Executor executor,
                                              CallbackInfoReturnable<SpriteLoader.Preparations> callback) {
        boolean global=TextureAtlas.LOCATION_BLOCKS.equals(location) && MipmapOverride.global();
        if(mipLevel!=MipmapOverride.LEVEL || !global && !MipmappedBlockAtlas.LOCATION.equals(location)) return;
        int resized=0;
        for(var sprite:sprites)
            if(((MipmapSpriteContents)sprite).faraway$alignFramesForMipmaps(mipLevel)) resized++;
        if(global) MipmapOverride.recordGlobalSpriteResize(resized>0);
        if(resized>0) FarAwayClient.LOGGER.info("FarAway aligned {} sprites for mip level {} in {}",resized,mipLevel,location);
    }
}
