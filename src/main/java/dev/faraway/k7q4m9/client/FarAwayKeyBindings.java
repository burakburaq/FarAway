package dev.faraway.k7q4m9.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayDeque;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Modifier-aware shortcuts; actions retain the modifiers present at the key-down event. */
public final class FarAwayKeyBindings {
    public enum Action { FREECAM, DEBUGCAM, SETTINGS }
    private static KeyMapping freecam,debugcam,settings;
    private static final ArrayDeque<Action> pending=new ArrayDeque<>();
    private FarAwayKeyBindings() {}
    public static void register() {
        var category=KeyMapping.Category.register(Identifier.parse("faraway:general"));
        freecam=KeyMappingHelper.registerKeyMapping(new Shortcut("key.faraway.freecam",InputConstants.UNKNOWN.getValue(),category,false));
        debugcam=KeyMappingHelper.registerKeyMapping(new Shortcut("key.faraway.debugcam",InputConstants.UNKNOWN.getValue(),category,true));
        settings=KeyMappingHelper.registerKeyMapping(new Shortcut("key.faraway.open_settings",InputConstants.KEY_H,category,true));
    }
    public static Action poll() { return pending.pollFirst(); }
    public static boolean handle(long window,int action,KeyEvent event) {
        var client=Minecraft.getInstance();
        if(freecam==null || window!=client.getWindow().handle() || client.level==null || client.gui.screen()!=null || action==InputConstants.RELEASE)return false;
        Action selected=settings.matches(event)?Action.SETTINGS:debugcam.matches(event)?Action.DEBUGCAM:freecam.matches(event)?Action.FREECAM:null;
        if(selected==null)return false;
        if(action==InputConstants.PRESS)pending.addLast(selected);
        // Consume repeats without toggling repeatedly while the key is held.
        return true;
    }
    private static final class Shortcut extends KeyMapping {
        private final boolean control;
        Shortcut(String name,int key,Category category,boolean control) { super(name,key,category);this.control=control; }
        @Override public boolean matches(KeyEvent event) { return event.hasControlDown()==control && super.matches(event); }
        @Override public Component getTranslatedKeyMessage() { return control&&!isUnbound()?Component.literal("Ctrl + ").append(super.getTranslatedKeyMessage()):super.getTranslatedKeyMessage(); }
        @Override public boolean same(KeyMapping other) { return (other instanceof Shortcut s?control==s.control:!control) && super.same(other); }
    }
}
