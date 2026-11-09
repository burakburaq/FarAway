package dev.faraway.k7q4m9.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.faraway.k7q4m9.client.ClientOptionLocks;
import dev.faraway.k7q4m9.client.SodiumOptionIdentity;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets="net.caffeinemc.mods.sodium.client.gui.widgets.OptionListWidget",remap=false)
abstract class SodiumOptionListMixin {
    @WrapOperation(method="renderAllPages",at=@At(value="INVOKE",
        target="Lnet/caffeinemc/mods/sodium/client/config/structure/OptionGroup;options()Ljava/util/List;"))
    private List<?> faraway$filterOptionGroup(@Coerce Object group,Operation<List<?>> original) {
        var options=original.call(group);
        if(!ClientOptionLocks.hiding()) return options;
        return options.stream().filter(option ->
            ClientOptionLocks.sodiumValue(((SodiumOptionIdentity)option).faraway$optionId())==null).toList();
    }

    @ModifyExpressionValue(method="renderFilteredOptions",at=@At(value="FIELD",
        target="Lnet/caffeinemc/mods/sodium/client/gui/widgets/OptionListWidget;filteredOptions:Ljava/util/List;"))
    private List<?> faraway$filterSearchResults(List<?> matches) {
        if(matches.isEmpty() || !ClientOptionLocks.hiding()) return matches;
        var visible=new ArrayList<>(matches.size());
        try {
            // The pinned Sodium version exposes this on its internal search entry type.
            var getOption=matches.getFirst().getClass().getMethod("getOption");
            for(var match:matches) {
                var option=(SodiumOptionIdentity)getOption.invoke(match);
                if(ClientOptionLocks.sodiumValue(option.faraway$optionId())==null) visible.add(match);
            }
        } catch(ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not filter locked Sodium options",failure);
        }
        return visible;
    }
}
