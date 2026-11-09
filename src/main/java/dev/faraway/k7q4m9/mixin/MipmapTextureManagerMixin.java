package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.client.FarAwayClient;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureManager.class)
abstract class MipmapTextureManagerMixin {
    @Inject(method="tick",at=@At("TAIL"))
    private void faraway$tickLodAtlas(CallbackInfo callback) {
        FarAwayClient.tickBlockTextures();
    }
}
