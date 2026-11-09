package dev.faraway.k7q4m9.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.faraway.k7q4m9.client.FarAwayClient;
import dev.faraway.k7q4m9.client.LodSettings;
import dev.faraway.k7q4m9.client.LodSettings.MipmapMode;
import dev.faraway.k7q4m9.client.MipmapOverride;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;

/** Render-thread-owned samplers; never mutate Minecraft's atlas or lightmap sampler. */
final class BlockTextureFiltering implements AutoCloseable {
    private record Key(int maxMip,int anisotropy) {}
    private final Map<Key,GpuSampler> samplers=new HashMap<>();
    private Key logged;

    GpuSampler sampler(TextureAtlas atlas) {
        var device=RenderSystem.getDevice();
        var settings=LodSettings.get();
        int requested=settings.anisotropicFiltering();
        int supported=Math.max(1,device.getDeviceInfo().limits().maxAnisotropy());
        int anisotropy=Integer.highestOneBit(Math.min(requested,supported));
        int desiredMip=MipmapOverride.effectiveMode(settings)==MipmapMode.OFF?Minecraft.getInstance().options.mipmapLevels().get():MipmapOverride.LEVEL;
        int maxMip=Math.min(atlas.getTextureView().mipLevels()-1,desiredMip);
        var key=new Key(maxMip,anisotropy);
        var sampler=samplers.get(key);
        if(sampler==null) {
            try {
                // Linear minification blends within/between mips; magnification stays pixel-sharp.
                sampler=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR,FilterMode.NEAREST,anisotropy,OptionalDouble.of(maxMip));
            } catch(RuntimeException failure) {
                if(anisotropy==1)throw failure;
                FarAwayClient.LOGGER.warn("FarAway anisotropic filtering unavailable at {}x; retaining mipmaps without anisotropy",anisotropy,failure);
                var fallback=new Key(maxMip,1);
                sampler=samplers.get(fallback);
                if(sampler==null) {
                    sampler=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,
                        FilterMode.LINEAR,FilterMode.NEAREST,1,OptionalDouble.of(maxMip));
                    samplers.put(fallback,sampler);
                }
            }
            samplers.put(key,sampler);
        }
        if(!key.equals(logged)) {
            logged=key;
            FarAwayClient.LOGGER.info("FarAway block/water filtering maxMip={} availableMips={} anisotropy={}x requested={}x supported={}x{}",
                maxMip,atlas.getTextureView().mipLevels(),sampler.getMaxAnisotropy(),requested,supported,
                maxMip==0?" (Minecraft mipmaps are disabled or atlas has no lower mips)":"");
        }
        return sampler;
    }

    @Override public void close() {
        var retired=new HashSet<>(samplers.values());samplers.clear();logged=null;
        // Old draws may still reference a sampler when settings, world or resources change.
        if(!retired.isEmpty())RenderSystem.queueFencedTask(()->retired.forEach(GpuSampler::close));
    }
}
