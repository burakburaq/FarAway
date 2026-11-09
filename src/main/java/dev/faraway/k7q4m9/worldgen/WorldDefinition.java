package dev.faraway.k7q4m9.worldgen;

import dev.faraway.k7q4m9.client.LodSettings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/** Captured on the integrated server thread. Frozen registries are read-only. */
public record WorldDefinition(long seed, String fingerprint, RegistryAccess.Frozen registries,
                              ChunkGenerator generator, RandomState randomState, Mode mode, LevelHeightAccessor heights,
                              net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates,
                              boolean gpuSamplingCompatible, ServerLevel sourceLevel) {
    public enum Mode { NOISE_BASED, SUPERFLAT, LOADED_CHUNKS }

    public boolean detachedDecorations() { return mode!=Mode.LOADED_CHUNKS; }
    public NoiseBasedChunkGenerator noiseGenerator() { return (NoiseBasedChunkGenerator)generator; }

    public static WorldDefinition capture(ServerLevel level) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Capture must run on server thread");
        var chunkSource=level.getChunkSource();
        var generator=chunkSource.getGenerator();
        var registries=level.getServer().registryAccess();
        Mode mode;
        if(generator.getClass()==NoiseBasedChunkGenerator.class) mode=Mode.NOISE_BASED;
        else if(generator.getClass()==FlatLevelSource.class) mode=Mode.SUPERFLAT;
        else mode=Mode.LOADED_CHUNKS;
        if(mode!=Mode.LOADED_CHUNKS) chunkSource.getGeneratorState().ensureStructuresGenerated();
        // The approximate GPU shader only supports stock Overworld inputs.
        // This check never selects a CPU generation path.
        boolean gpuCompatible=mode==Mode.NOISE_BASED && LodSettings.get().gpuSurfaceSampling()
            && supportsStockOverworld(level,(NoiseBasedChunkGenerator)generator);
        String identity="26.3/surface-3/"+level.dimension().identifier()+"/"+level.getSeed()+"/"+java.util.UUID.randomUUID();
        return new WorldDefinition(level.getSeed(),sha256(identity),registries,generator,chunkSource.randomState(),mode,
            LevelHeightAccessor.create(level.getMinY(),level.getHeight()),level.getStructureTemplateManager(),gpuCompatible,level);
    }

    boolean stockOverworld() {
        return mode==Mode.NOISE_BASED && sourceLevel!=null && supportsStockOverworld(sourceLevel,noiseGenerator());
    }

    private static boolean supportsStockOverworld(ServerLevel level,NoiseBasedChunkGenerator noise) {
        if(!level.dimension().equals(Level.OVERWORLD) || !noise.stable(NoiseGeneratorSettings.OVERWORLD)
            || !(noise.getBiomeSource() instanceof MultiNoiseBiomeSource biomes)
            || !biomes.stable(MultiNoiseBiomeSourceParameterLists.OVERWORLD)) return false;
        var packs=level.getServer().getWorldData().getDataConfiguration().dataPacks().getEnabled();
        var builtinPacks=java.util.Set.of("vanilla","fabric","fabric-convention-tags-v2","fabric-gametest-api-v1");
        return packs.stream().allMatch(builtinPacks::contains) || !hasWorldgenOverrides(level,builtinPacks);
    }

    private static boolean hasWorldgenOverrides(ServerLevel level,java.util.Set<String> builtinPacks) {
        var resources=level.getServer().getResourceManager();
        for(String path:java.util.List.of("worldgen","dimension","dimension_type","tags/worldgen","tags/block","structure")) {
            if(resources.listResources(path,id -> true).values().stream().anyMatch(resource -> !builtinPacks.contains(resource.sourcePackId()))) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
