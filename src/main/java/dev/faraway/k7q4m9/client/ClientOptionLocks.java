package dev.faraway.k7q4m9.client;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.resources.Identifier;
import com.mojang.renderpearl.api.textures.FilterMode;

/** Effective client values kept separate from the player's serialized preferences. */
public final class ClientOptionLocks {
    public static final int SODIUM_THREADS=0;
    public static final boolean BLOCK_FACE_CULLING=true, ENTITY_CULLING=true;
    public static final FilterMode TEXEL_INTERPOLATION=FilterMode.NEAREST;
    private static final int DISTANCE=12, CLOUD_DISTANCE=32, VANILLA_BIOME_BLEND=0;
    private static final CloudStatus CLOUDS=CloudStatus.FANCY;
    private static final boolean SMOOTH_LIGHTING=true;
    private static final double CHUNK_FADE_SECONDS=0.0;
    private static final ThreadLocal<Boolean> OPTIONS_IO=new ThreadLocal<>();
    private static volatile Map<OptionInstance<?>,Object> vanilla=Map.of();
    private static volatile OptionInstance<Integer> mipmapOption;

    private ClientOptionLocks() {}

    public static void bind(Options options) {
        var values=new IdentityHashMap<OptionInstance<?>,Object>();
        values.put(options.simulationDistance(),DISTANCE);
        values.put(options.renderDistance(),DISTANCE);
        values.put(options.cloudStatus(),CLOUDS);
        values.put(options.cloudRange(),CLOUD_DISTANCE);
        values.put(options.biomeBlendRadius(),VANILLA_BIOME_BLEND);
        values.put(options.ambientOcclusion(),SMOOTH_LIGHTING);
        values.put(options.chunkSectionFadeInTime(),CHUNK_FADE_SECONDS);
        mipmapOption=options.mipmapLevels();
        vanilla=values;
    }

    public static boolean enabled() { return LodSettings.get().lockedOptions(); }

    public static boolean hiding() {
        var settings=LodSettings.get();
        return settings.lockedOptions() && settings.hideLockedOptions();
    }

    public static void settingsApplied(boolean previouslyLocked) {
        if(previouslyLocked==enabled()) return;
        var client=Minecraft.getInstance();
        client.options.broadcastOptions();
        client.levelExtractor.resetSampler();
        if(client.level!=null) client.levelExtractor.allChanged();
    }

    public static Object vanillaValue(Object option) {
        Object value=vanilla.get(option);
        if(value!=null && !enabled()) return null;
        if(value==null && option==mipmapOption && MipmapOverride.global()) value=MipmapOverride.LEVEL;
        return value!=null && OPTIONS_IO.get()==null?value:null;
    }

    public static Object sodiumValue(Identifier option) {
        if(!option.getNamespace().equals("sodium") || !enabled()) return null;
        return switch(option.getPath()) {
            case "general.simulation_distance", "general.render_distance" -> DISTANCE;
            case "quality.clouds" -> CLOUDS;
            case "quality.render_cloud_distance" -> CLOUD_DISTANCE;
            case "quality.biome_blend" -> VANILLA_BIOME_BLEND;
            case "quality.ao" -> SMOOTH_LIGHTING;
            case "quality.fade_time" -> 0;
            case "quality.mipmap_levels" -> MipmapOverride.global()?MipmapOverride.LEVEL:null;
            case "performance.chunk_update_threads" -> SODIUM_THREADS;
            case "performance.use_block_face_culling" -> BLOCK_FACE_CULLING;
            case "performance.use_entity_culling" -> ENTITY_CULLING;
            case "quality.pixel_filtering_mode" -> TEXEL_INTERPOLATION;
            default -> null;
        };
    }

    public static void withStoredPreferences(Runnable action) {
        if(OPTIONS_IO.get()!=null) {
            action.run();
            return;
        }
        OPTIONS_IO.set(true);
        try { action.run(); }
        finally { OPTIONS_IO.remove(); }
    }
}
