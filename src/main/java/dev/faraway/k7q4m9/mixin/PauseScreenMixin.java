package dev.faraway.k7q4m9.mixin;

import dev.faraway.k7q4m9.client.LodSettingsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    protected PauseScreenMixin(Component title) { super(title); }
    @Inject(method="init",at=@At("TAIL"))
    private void farawayAddSettings(CallbackInfo ci) {
        if(!((PauseScreen)(Object)this).showsPauseMenu()) return;
        SpriteIconButton last=null;
        for(var child:children()) if(child instanceof SpriteIconButton button && (last==null || button.getX()>last.getX())) last=button;
        if(last==null) return;
        int left=last.getX()+last.getWidth()+4,buttonWidth=width/2+102-left;
        if(buttonWidth<font.width("FarAway")+8) return;
        addRenderableWidget(Button.builder(Component.literal("FarAway"),b -> minecraft.gui.setScreen(new LodSettingsScreen(this)))
            .bounds(left,last.getY(),buttonWidth,20).build());
    }
}
