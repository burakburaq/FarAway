package dev.faraway.k7q4m9.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.faraway.k7q4m9.client.ClientOptionLocks;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
abstract class ClientOptionsMixin {
    @Inject(method="<init>",at=@At("RETURN"))
    private void faraway$bindLockedOptions(CallbackInfo callback) {
        ClientOptionLocks.bind((Options)(Object)this);
    }

    @WrapOperation(method={"load","save"},at=@At(value="INVOKE",
        target="Lnet/minecraft/client/Options;processOptions(Lnet/minecraft/client/Options$FieldAccess;)V"))
    private void faraway$useStoredPreferences(Options options,@Coerce Object access,Operation<Void> original) {
        ClientOptionLocks.withStoredPreferences(() -> original.call(options,access));
    }
}
