package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.client.MipmapOverride;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(AtlasManager.class)
abstract class MipmapAtlasManagerMixin {
    @ModifyVariable(method="updateMaxMipLevel",at=@At("HEAD"),argsOnly=true)
    private int faraway$lockAtlasMipmapLevel(int requested) {
        return MipmapOverride.effective(requested);
    }
}
