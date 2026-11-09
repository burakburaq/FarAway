package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.client.ClientOptionLocks;
import java.util.Arrays;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.OptionsList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OptionsList.class)
abstract class ClientOptionsListMixin {
    @ModifyVariable(method="addSmall([Lnet/minecraft/client/OptionInstance;)V",at=@At("HEAD"),argsOnly=true)
    private OptionInstance<?>[] faraway$filterLockedOptions(OptionInstance<?>[] options) {
        if(!ClientOptionLocks.hiding()) return options;
        return Arrays.stream(options).filter(option -> ClientOptionLocks.vanillaValue(option)==null)
            .toArray(OptionInstance<?>[]::new);
    }

    @Inject(method="addBig(Lnet/minecraft/client/OptionInstance;)V",at=@At("HEAD"),cancellable=true)
    private void faraway$hideLockedOption(OptionInstance<?> option,CallbackInfo callback) {
        if(ClientOptionLocks.hiding() && ClientOptionLocks.vanillaValue(option)!=null) callback.cancel();
    }
}
