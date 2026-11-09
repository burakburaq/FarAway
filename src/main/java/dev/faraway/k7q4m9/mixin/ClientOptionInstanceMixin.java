package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.client.ClientOptionLocks;
import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OptionInstance.class)
abstract class ClientOptionInstanceMixin {
    @Inject(method="get",at=@At("HEAD"),cancellable=true)
    private void faraway$effectiveValue(CallbackInfoReturnable<Object> callback) {
        Object value=ClientOptionLocks.vanillaValue(this);
        if(value!=null) callback.setReturnValue(value);
    }

    @Inject(method="set",at=@At("HEAD"),cancellable=true)
    private void faraway$preventLockedWrite(Object value,CallbackInfo callback) {
        if(ClientOptionLocks.vanillaValue(this)!=null) callback.cancel();
    }

    @Inject(method="createButton(Lnet/minecraft/client/Options;IIILnet/minecraft/client/OptionInstance$ValueUpdateListener;)Lnet/minecraft/client/gui/components/AbstractWidget;",at=@At("RETURN"))
    private void faraway$lockWidget(Options options,int x,int y,int width,OptionInstance.ValueUpdateListener<?> listener,
                                    CallbackInfoReturnable<AbstractWidget> callback) {
        if(ClientOptionLocks.vanillaValue(this)==null) return;
        var widget=callback.getReturnValue();
        widget.active=false;
        widget.setMessage(widget.getMessage().copy().append(" (FA)").withStyle(ChatFormatting.STRIKETHROUGH));
    }
}
