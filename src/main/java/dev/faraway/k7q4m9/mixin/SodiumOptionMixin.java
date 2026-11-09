package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.client.ClientOptionLocks;
import dev.faraway.k7q4m9.client.SodiumOptionIdentity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets="net.caffeinemc.mods.sodium.client.config.structure.Option",remap=false)
abstract class SodiumOptionMixin implements SodiumOptionIdentity {
    @Shadow @Final private Identifier id;

    @Override public Identifier faraway$optionId() { return id; }

    @Inject(method="isEnabled",at=@At("HEAD"),cancellable=true)
    private void faraway$lockWidget(CallbackInfoReturnable<Boolean> callback) {
        if(ClientOptionLocks.sodiumValue(id)!=null) callback.setReturnValue(false);
    }

    @Inject(method="getName",at=@At("RETURN"),cancellable=true)
    private void faraway$labelLock(CallbackInfoReturnable<Component> callback) {
        if(ClientOptionLocks.sodiumValue(id)!=null)
            callback.setReturnValue(callback.getReturnValue().copy().append(" (FA)"));
    }
}
