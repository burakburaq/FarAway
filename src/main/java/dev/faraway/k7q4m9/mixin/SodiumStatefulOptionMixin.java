package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.client.ClientOptionLocks;
import dev.faraway.k7q4m9.client.SodiumOptionIdentity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets="net.caffeinemc.mods.sodium.client.config.structure.StatefulOption",remap=false)
abstract class SodiumStatefulOptionMixin {
    @Unique private Object faraway$lockedValue() {
        return ClientOptionLocks.sodiumValue(((SodiumOptionIdentity)(Object)this).faraway$optionId());
    }

    @Inject(method={"getValidatedValue","getAppliedValue"},at=@At("HEAD"),cancellable=true)
    private void faraway$effectiveValue(CallbackInfoReturnable<Object> callback) {
        Object value=faraway$lockedValue();
        if(value!=null) callback.setReturnValue(value);
    }

    @Inject(method="modifyValue",at=@At("HEAD"),cancellable=true)
    private void faraway$preventLockedWrite(Object value,CallbackInfo callback) {
        if(faraway$lockedValue()!=null) callback.cancel();
    }

    @Inject(method="shouldHideControl",at=@At("HEAD"),cancellable=true)
    private void faraway$setLockedControlVisibility(CallbackInfoReturnable<Boolean> callback) {
        if(faraway$lockedValue()!=null) callback.setReturnValue(ClientOptionLocks.hiding());
    }

    @Inject(method="applyChanges",at=@At("HEAD"),cancellable=true)
    private void faraway$preventLockedApply(CallbackInfoReturnable<Boolean> callback) {
        if(faraway$lockedValue()!=null) callback.setReturnValue(false);
    }
}
