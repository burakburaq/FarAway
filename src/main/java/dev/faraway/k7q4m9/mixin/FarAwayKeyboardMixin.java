package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.client.FarAwayKeyBindings;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class FarAwayKeyboardMixin {
    @Inject(method="keyPress",at=@At("HEAD"),cancellable=true)
    private void farawayShortcuts(long window,int action,KeyEvent event,CallbackInfo ci) {
        if(FarAwayKeyBindings.handle(window,action,event))ci.cancel();
    }
}
