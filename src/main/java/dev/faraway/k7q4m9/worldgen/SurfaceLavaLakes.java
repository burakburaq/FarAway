package dev.faraway.k7q4m9.worldgen;

import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Phase;
import dev.faraway.k7q4m9.diagnostics.WorldgenCacheProfile.Kind;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.RarityFilter;

/** Replays only the surface lava lake feature in detached chunks. */
final class SurfaceLavaLakes {
    private static final int STEP=GenerationStep.Decoration.LAKES.ordinal();
    private static final ResourceKey<PlacedFeature> SURFACE_LAVA=ResourceKey.create(
        Registries.PLACED_FEATURE,Identifier.withDefaultNamespace("lake_lava_surface"));
    private final WorldDefinition world;
    private final BooleanSupplier closed;
    private final Supplier<List<FeatureSorter.StepFeatureData>> features;
    private final WorldTerrainSampler.ChunkCache<Map<Long,WorldTerrainSampler.Surface>> placed=new WorldTerrainSampler.ChunkCache<>(Kind.LAVA_LAKES,4096);
    private volatile Feature feature;

    private record Feature(PlacedFeature placed,int index) {}
    @FunctionalInterface interface Terrain { ProtoChunk build(ChunkPos pos,boolean adapted); }
    @FunctionalInterface interface ColumnReader { WorldTerrainSampler.Surface read(ProtoChunk chunk,int x,int z); }

    SurfaceLavaLakes(WorldDefinition world,BooleanSupplier closed,Supplier<List<FeatureSorter.StepFeatureData>> features) {
        this.world=world;
        this.closed=closed;
        this.features=features;
    }

    private Feature feature() {
        var current=feature;
        if(current!=null) return current;
        synchronized(this) {
            if(feature!=null) return feature;
            var holder=world.registries().lookupOrThrow(Registries.PLACED_FEATURE).get(SURFACE_LAVA);
            if(holder.isEmpty() || !(holder.get().value().feature().value() instanceof LakeFeature)
                || world.generator().getBiomeSource().possibleBiomes().stream().noneMatch(biome -> {
                var steps=world.generator().getBiomeGenerationSettings(biome).features();
                return STEP<steps.size() && steps.get(STEP).contains(holder.get());
            })) return feature=new Feature(null,-1);
            var value=holder.get().value();
            return feature=new Feature(value,features.get().get(STEP).indexMapping().applyAsInt(value));
        }
    }

    Map<Long,WorldTerrainSampler.Surface> columnsInChunk(ChunkPos destination,boolean adapted,boolean onDemand,Terrain terrain,ColumnReader reader) {
        var selected=feature();
        if(selected.placed()==null) return Map.of();
        Map<Long,WorldTerrainSampler.Surface> result=null;
        for(int z=destination.z()-1;z<=destination.z()+1;z++) for(int x=destination.x()-1;x<=destination.x()+1;x++) {
            var source=new ChunkPos(x,z);
            var key=new WorldTerrainSampler.ChunkKey(source.pack(),adapted,onDemand);
            var columns=placed.get(key,() -> WorldgenProfiler.measure(Phase.LAKES,() -> place(source,adapted,onDemand,selected,terrain,reader)));
            if(columns.isEmpty()) continue;
            for(var entry:columns.entrySet()) {
                int wx=SurfaceSampleCache.x(entry.getKey()),wz=SurfaceSampleCache.z(entry.getKey());
                if((wx>>4)!=destination.x() || (wz>>4)!=destination.z()) continue;
                if(result==null) result=new HashMap<>();
                result.put(entry.getKey(),entry.getValue());
            }
        }
        return result==null?Map.of():result;
    }

    private Map<Long,WorldTerrainSampler.Surface> place(ChunkPos source,boolean adapted,boolean onDemand,Feature selected,Terrain terrain,ColumnReader reader) {
        check();
        var origin=new BlockPos(source.getMinBlockX(),Math.floorDiv(world.heights().getMinY(),16)*16,source.getMinBlockZ());
        var rng=new WorldgenRandom(new XoroshiroRandomSource(0));
        long seed=rng.setDecorationSeed(world.seed(),origin.getX(),origin.getZ());
        rng.setFeatureSeed(seed,selected.index(),STEP);
        var modifiers=selected.placed().placement();
        if(!modifiers.isEmpty() && modifiers.getFirst() instanceof RarityFilter rarity && !rarity.shouldPlace(null,rng,origin)) return Map.of();
        // Reset so Minecraft consumes the same random values through all placement modifiers.
        rng.setFeatureSeed(seed,selected.index(),STEP);
        var chunks=new HashMap<Long,ProtoChunk>();
        java.util.function.BiFunction<Integer,Integer,ProtoChunk> chunk=(x,z) -> chunks.computeIfAbsent(ChunkPos.pack(x,z),key -> {
            check();
            return terrain.build(ChunkPos.unpack(key),adapted);
        });
        var changed=new HashSet<Long>();
        var region=new ScratchWorldGenRegion(world,source,chunk,onDemand) {
            // The scratch chunks contain terrain only. Resolve biomes from the
            // live dimension's seeded resolver, rather than their default palette.
            @Override public Holder<Biome> getNoiseBiome(int x,int y,int z) {
                return getUncachedNoiseBiome(x,y,z);
            }

            @Override public boolean setBlock(BlockPos pos,BlockState state,int flags,int limit) {
                if(!isWithinWriteZone(pos) || isOutsideBuildHeight(pos)) return false;
                var target=getChunk(pos);
                if(target.setBlockState(pos,state,flags)!=state) changed.add(SurfaceSampleCache.key(pos.getX(),pos.getZ()));
                return true;
            }
        };
        boolean generated=new FeaturePlacer(region,world.generator()).placeWithBiomeCheck(selected.placed(),rng,origin);
        dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.debug("FarAway surface lava lake sourceChunk={},{} columns={} onDemand={} scratchChunks={}",
            source.x(),source.z(),generated?changed.size():0,onDemand,chunks.size());
        if(!generated || changed.isEmpty()) return Map.of();
        var result=new HashMap<Long,WorldTerrainSampler.Surface>();
        for(long column:changed) {
            int x=SurfaceSampleCache.x(column),z=SurfaceSampleCache.z(column);
            result.put(column,reader.read(chunk.apply(x>>4,z>>4),x,z));
        }
        return Map.copyOf(result);
    }

    private void check() { if(closed.getAsBoolean()) throw new CancellationException(); }
}
