package dev.faraway.k7q4m9.worldgen;

import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Phase;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import dev.faraway.k7q4m9.terrain.TileKey;

/** Surface structures and tree features, placed in disposable RAM-only chunks.
 * One instance belongs to one worker. Never loads, saves or mutates live-world chunks/POIs. */
public final class DetachedDecorationSampler {
    public static final int TREE_REGION_BLOCKS=64;
    public static final int TREE_WRITE_MARGIN=16;

    public interface TreeTrunkSink {
        void recordTreeTrunk(BlockPos pos,BlockState state);
    }

    public record Result(Map<BlockPos,BlockState> blocks, int logBlocks, int structures) {}
    private final WorldDefinition world;
    private final ChunkGenerator generator;
    private final RandomState random;
    private final PalettedContainerFactory containers;
    private final StructurePieceSerializationContext serialization;
    private final StructureIndex index;
    private final DetachedTerrainCache terrainCache;
    private final WorldTerrainSampler surfaceTerrain;
    private record TreePlacement(PlacedFeature feature,int step,int index) {}
    private final Map<Holder<Biome>,List<TreePlacement>> biomeTrees=new HashMap<>();
    private List<net.minecraft.world.level.biome.FeatureSorter.StepFeatureData> features;
    private StructureIndex.Snapshot snapshot;

    public DetachedDecorationSampler(WorldDefinition world,StructureIndex index,DetachedTerrainCache terrainCache,WorldTerrainSampler surfaceTerrain) {
        this.surfaceTerrain=surfaceTerrain;
        this.index=index;
        this.terrainCache=terrainCache;
        this.world=world;
        generator=world.generator();
        random=world.randomState();
        containers=PalettedContainerFactory.create(world.registries());
        serialization=new StructurePieceSerializationContext(world.sourceLevel().getServer().getResourceManager(),world.registries(),world.templates());
    }
    public Result generateStructures(TileKey key,BooleanSupplier cancelled) {
        return generate(key,true,false,cancelled);
    }

    public Result generateTreeRegion(TileKey key,boolean includeStructures,BooleanSupplier cancelled) {
        return generate(key,includeStructures,true,cancelled);
    }

    private Result generate(TileKey key,boolean includeStructures,boolean includeTrees,BooleanSupplier cancelled) {
        if(key.spacing()!=1 || key.blocks()!=(includeTrees?TREE_REGION_BLOCKS:32))
            throw new IllegalArgumentException("Invalid decoration region size");
        long patchStart=System.nanoTime();
        check(cancelled);
        if(includeStructures)discover(key,cancelled);
        long indexDone=System.nanoTime();
        int cx=Math.floorDiv(key.originX(),16),cz=Math.floorDiv(key.originZ(),16),side=key.blocks()/16;
        var chunks=new HashMap<Long,ProtoChunk>();
        java.util.function.BiFunction<Integer,Integer,ProtoChunk> chunk=(x,z) -> chunks.computeIfAbsent(ChunkPos.pack(x,z), ignored ->
            new ProtoChunk(new ChunkPos(x,z),UpgradeData.EMPTY,world.heights(),containers,null));
        // Copy starts because postProcess mutates piece height/chest flags.
        var starts=new ArrayList<StructureStart>();
        if(includeStructures)for(var f:snapshot.starts()) if(f.start().getBoundingBox().intersects((cx-2)*16,(cz-2)*16,(cx+side+2)*16-1,(cz+side+2)*16-1)) {
            if(!includeTrees && f.start().getStructure() instanceof net.minecraft.world.level.levelgen.structure.structures.JigsawStructure) {
                int minX=key.originX()-16,minZ=key.originZ()-16,maxX=key.originX()+47,maxZ=key.originZ()+47;
                if(f.start().getPieces().stream().noneMatch(piece -> piece.getBoundingBox().intersects(minX,minZ,maxX,maxZ))) continue;
            }
            var start=StructureStart.loadStaticStart(serialization,f.start().createTag(serialization,f.startChunk()),world.seed());
            if(start!=null && start.isValid()) starts.add(start);
        }
        if(starts.isEmpty() && !includeTrees) return new Result(Map.of(),0,0);
        if(!includeTrees) {
            int minX=key.originX()-16,minZ=key.originZ()-16,maxX=key.originX()+47,maxZ=key.originZ()+47;
            boolean relevant=starts.stream().flatMap(s -> s.getPieces().stream()).anyMatch(piece ->
                piece instanceof net.minecraft.world.level.levelgen.structure.structures.RuinedPortalPiece
                    || piece.getBoundingBox().intersects(minX,minZ,maxX,maxZ));
            if(!relevant) return new Result(Map.of(),0,0);
        }
        // Ruined portals can write beyond their source chunk; trees can cross chunk
        // boundaries too. Other supported structures place within their chunk BB.
        boolean extended=includeTrees || starts.stream().flatMap(s -> s.getPieces().stream())
            .anyMatch(p -> p instanceof net.minecraft.world.level.levelgen.structure.structures.RuinedPortalPiece);
        // The FEATURES write radius is one chunk. Simulate adjacent origins for
        // collision checks and only publish trees placed by origins in the core.
        int decorationHalo=extended?1:0,terrainHalo=decorationHalo+1,biomeHalo=terrainHalo+1;
        long timingStart=System.nanoTime();
        var changes=new HashMap<BlockPos,BlockState>();
        var trees=new HashMap<BlockPos,BlockState>();
        boolean[][] active=new boolean[side+2*decorationHalo][side+2*decorationHalo];
        var treePlans=new HashMap<Long,List<TreePlacement>>();
        boolean portals=starts.stream().flatMap(start -> start.getPieces().stream())
            .anyMatch(piece -> piece instanceof net.minecraft.world.level.levelgen.structure.structures.RuinedPortalPiece);
        for(int z=0;z<active.length;z++) for(int x=0;x<active[z].length;x++) {
            check(cancelled);
            int chunkX=cx+x-decorationHalo,chunkZ=cz+z-decorationHalo;
            var pos=new ChunkPos(chunkX,chunkZ);
            if(includeTrees) {
                var plan=WorldgenProfiler.measure(Phase.TREE_CANDIDATES,() -> planTrees(pos));
                if(!plan.isEmpty()) treePlans.put(pos.pack(),plan);
            }
            active[z][x]=treePlans.containsKey(pos.pack()) || portals || starts.stream().flatMap(start -> start.getPieces().stream())
                .anyMatch(piece -> piece.getBoundingBox().intersects(pos.getMinBlockX(),pos.getMinBlockZ(),pos.getMaxBlockX(),pos.getMaxBlockZ()));
        }
        if(includeTrees && treePlans.isEmpty()) return new Result(Map.of(),0,0);
        boolean[][] terrainNeeded=expandedMask(active,1),biomesNeeded=expandedMask(terrainNeeded,1);
        long cacheLimit=Math.max(512L*1024,dev.faraway.k7q4m9.client.LodSettings.toBytes(dev.faraway.k7q4m9.client.LodSettings.effectiveHeapGiB())/8);
        long biomesDone=System.nanoTime();
        BiFunction<Integer,Integer,ProtoChunk> terrainChunk=chunk;
        if(includeTrees) {
            // A rejected placement often touches no neighboring ground. Build
            // a mutable substrate only when vanilla placement reads that chunk.
            terrainChunk=(x,z) -> chunks.computeIfAbsent(ChunkPos.pack(x,z),unused ->
                placementChunk(new ProtoChunk(new ChunkPos(x,z),UpgradeData.EMPTY,world.heights(),containers,null),cacheLimit,cancelled));
        } else {
            // Preserve the structure-only placement neighborhood.
            for(int z=cz-biomeHalo;z<=cz+side-1+biomeHalo;z++) for(int x=cx-biomeHalo;x<=cx+side-1+biomeHalo;x++) {
                if(!biomesNeeded[z-(cz-biomeHalo)][x-(cx-biomeHalo)]) continue;
                check(cancelled);
                var target=chunk.apply(x,z);
                WorldgenProfiler.await(() -> generator.createBiomes(random,Blender.empty(),null,target).join());
                target.setPersistedStatus(ChunkStatus.BIOMES);
            }
            biomesDone=System.nanoTime();
            for(int z=cz-terrainHalo;z<=cz+side-1+terrainHalo;z++) for(int x=cx-terrainHalo;x<=cx+side-1+terrainHalo;x++) {
                if(!terrainNeeded[z-(cz-terrainHalo)][x-(cx-terrainHalo)]) continue;
                var target=chunk.apply(x,z);
                chunks.put(target.getPos().pack(),placementChunk(target,cacheLimit,cancelled));
            }
        }
        long terrainDone=System.nanoTime(),structureNs=0,treeNs=0;
        for(int z=cz-decorationHalo;z<=cz+side-1+decorationHalo;z++) for(int x=cx-decorationHalo;x<=cx+side-1+decorationHalo;x++) {
            if(!active[z-(cz-decorationHalo)][x-(cx-decorationHalo)]) continue;
            check(cancelled);
            var pos=new ChunkPos(x,z);
            long phase=System.nanoTime();
            if(includeStructures && !starts.isEmpty()) {
                var region=region(x,z,terrainChunk,includeTrees?null:changes,false,includeTrees && surfaceTerrain.scratchTerrainMode().trees());
                placeStructures(region,terrainChunk.apply(x,z),manager(region,starts),cancelled);
            }
            structureNs+=System.nanoTime()-phase;
            phase=System.nanoTime();
            var plan=treePlans.get(pos.pack());
            if(plan!=null) {
                boolean core=x>=cx && x<cx+side && z>=cz && z<cz+side;
                try(var profile=WorldgenProfiler.scope(Phase.TREES)) {
                    placeTrees(region(x,z,terrainChunk,core?trees:null,true,surfaceTerrain.scratchTerrainMode().trees()),pos,plan,cancelled);
                }
            }
            treeNs+=System.nanoTime()-phase;
            terrainChunk.apply(x,z).setPersistedStatus(ChunkStatus.FEATURES);
        }
        if(includeTrees) {
            dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.debug("FarAway tree region key={} planningMs={} placementAndTerrainMs={} sourceChunks={} scratchChunks={} blocks={} onDemand={}",
                key,(biomesDone-timingStart)/1_000_000,(System.nanoTime()-terrainDone)/1_000_000,treePlans.size(),chunks.size(),trees.size(),surfaceTerrain.scratchTerrainMode().trees());
            return new Result(Map.copyOf(trees),0,0);
        }
        var result=new HashMap<BlockPos,BlockState>();
        int logs=0;
        for(var entry:changes.entrySet()) {
            var p=entry.getKey(); var state=entry.getValue();
            if(p.getX()<key.originX() || p.getX()>=key.originX()+32 || p.getZ()<key.originZ() || p.getZ()>=key.originZ()+32 || state.isAir()
                || (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock && state.getFluidState().is(net.minecraft.tags.FluidTags.WATER))) continue;
            result.put(p,state);
            if(state.is(net.minecraft.tags.BlockTags.LOGS)) logs++;
        }
        dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.debug("FarAway patch timing mode=surface_band depth=8 key={} totalMs={} indexMs={} setupMs={} biomeMs={} terrainMs={} structureMs={} treeMs={} collectMs={} activeChunks={} terrainChunks={} biomeChunks={} blocks={}",key,
            (System.nanoTime()-patchStart)/1_000_000,(indexDone-patchStart)/1_000_000,(timingStart-indexDone)/1_000_000,(biomesDone-timingStart)/1_000_000,(terrainDone-biomesDone)/1_000_000,structureNs/1_000_000,treeNs/1_000_000,
            (System.nanoTime()-terrainDone-structureNs-treeNs)/1_000_000,countMask(active),countMask(terrainNeeded),countMask(biomesNeeded),result.size());
        return new Result(Map.copyOf(result),logs,starts.size());
    }
    private static int countMask(boolean[][] mask) { int count=0; for(var row:mask) for(boolean active:row) if(active) count++; return count; }
    static boolean[][] expandedMask(boolean[][] input,int padding) {
        int size=input.length+2*padding; var out=new boolean[size][size];
        for(int z=0;z<input.length;z++) for(int x=0;x<input[z].length;x++) if(input[z][x])
            for(int dz=0;dz<=2*padding;dz++) for(int dx=0;dx<=2*padding;dx++) out[z+dz][x+dx]=true;
        return out;
    }
    private ProtoChunk placementChunk(ProtoChunk target,long limit,BooleanSupplier cancelled) {
        for(;;) {
            check(cancelled);
            try(var profile=WorldgenProfiler.scope(Phase.SCRATCH_TERRAIN)) {
                return terrainCache.get(target.getPos(),world,containers,limit,surfaceTerrain.terrainAdapted(),() -> {
                    check(cancelled);
                    SurfaceTerrainBuilder.build(target,surfaceTerrain,cancelled);
                    check(cancelled);
                    return target;
                });
            } catch(java.util.concurrent.CancellationException stopped) { check(cancelled); }
        }
    }

    private List<TreePlacement> planTrees(ChunkPos pos) {
        if(features==null) features=surfaceTerrain.featureSteps();
        var available=new HashSet<Holder<Biome>>();
        for(int z=pos.z()-1;z<=pos.z()+1;z++) for(int x=pos.x()-1;x<=pos.x()+1;x++)
            available.addAll(surfaceTerrain.vegetationBiomes(new ChunkPos(x,z)));
        var candidates=new TreeSet<TreePlacement>(Comparator.comparingInt(TreePlacement::step).thenComparingInt(TreePlacement::index));
        for(var biome:available) candidates.addAll(biomeTrees.computeIfAbsent(biome,this::treeFeatures));
        if(candidates.isEmpty()) return List.of();
        var rng=new WorldgenRandom(new XoroshiroRandomSource(0));
        var origin=decorationOrigin(pos);
        long seed=rng.setDecorationSeed(world.seed(),origin.getX(),origin.getZ());
        var result=new ArrayList<TreePlacement>();
        for(var candidate:candidates) {
            var modifiers=candidate.feature().placement();
            if(!modifiers.isEmpty()) {
                rng.setFeatureSeed(seed,candidate.index(),candidate.step());
                // These first modifiers can prove a chunk has no tree candidates
                // without reading or preparing any terrain.
                var first=modifiers.getFirst();
                if(first instanceof CountPlacement count && count.count(rng,origin)<=0) continue;
                if(first instanceof RarityFilter rarity && !rarity.shouldPlace(null,rng,origin)) continue;
            }
            result.add(candidate);
        }
        return result;
    }

    private List<TreePlacement> treeFeatures(Holder<Biome> biome) {
        var result=new ArrayList<TreePlacement>();
        var steps=generator.getBiomeGenerationSettings(biome).features();
        for(int step=0;step<steps.size();step++) {
            var data=features.get(step);
            for(var holder:steps.get(step)) {
                var feature=holder.value();
                if(feature.getFeatures().anyMatch(f -> f.value() instanceof TreeFeature))
                    result.add(new TreePlacement(feature,step,data.indexMapping().applyAsInt(feature)));
            }
        }
        return result;
    }

    private BlockPos decorationOrigin(ChunkPos pos) {
        return new BlockPos(pos.getMinBlockX(),Math.floorDiv(world.heights().getMinY(),16)*16,pos.getMinBlockZ());
    }

    private void placeTrees(WorldGenLevel region,ChunkPos pos,List<TreePlacement> plan,BooleanSupplier cancelled) {
        var origin=decorationOrigin(pos);
        var rng=new WorldgenRandom(new XoroshiroRandomSource(0));
        long seed=rng.setDecorationSeed(world.seed(),origin.getX(),origin.getZ());
        var placer=new FeaturePlacer(region,generator);
        for(var candidate:plan) {
            check(cancelled);
            rng.setFeatureSeed(seed,candidate.index(),candidate.step());
            placer.placeWithBiomeCheck(candidate.feature(),rng,origin);
        }
    }
    public Set<TileKey> patches(TileKey key) {
        return index.patches(key);
    }
    private void discover(TileKey key,BooleanSupplier cancelled) {
        check(cancelled);
        snapshot=index.get(key);
        check(cancelled);
    }
    private void placeStructures(WorldGenLevel region,ChunkAccess chunk,StructureManager manager,BooleanSupplier cancelled) {
        var pos=chunk.getPos();
        var rng=new WorldgenRandom(new XoroshiroRandomSource(0));
        long decorationSeed=rng.setDecorationSeed(world.seed(),pos.getMinBlockX(),pos.getMinBlockZ());
        var registry=world.registries().lookupOrThrow(Registries.STRUCTURE);
        var writable=new BoundingBox(pos.getMinBlockX(),world.heights().getMinY()+1,pos.getMinBlockZ(),pos.getMaxBlockX(),world.heights().getMaxY(),pos.getMaxBlockZ());
        // Keep vanilla's registry order and per-step index even for skipped structures,
        // so supported structures receive their original placement random seed.
        for(var step:GenerationStep.Decoration.values()) {
            int index=0;
            for(var structure:registry) if(structure.step()==step) {
                check(cancelled);
                rng.setFeatureSeed(decorationSeed,index++,step.ordinal());
                for(var start:manager.startsForStructure(pos.x(),pos.z(),structure))
                    start.placeInChunk(region,manager,generator,rng,writable,pos);
            }
        }
    }
    private StructureManager manager(WorldGenLevel region,List<StructureStart> starts) {
        return new StructureManager(region,new WorldOptions(world.seed(),true,false),null) {
            @Override public List<StructureStart> startsForStructure(int x,int z,Predicate<Structure> predicate) {
                return starts.stream().filter(s -> predicate.test(s.getStructure()) && s.getBoundingBox().intersects(x*16,z*16,x*16+15,z*16+15)).toList();
            }
            @Override public List<StructureStart> startsForStructure(int x,int z,Structure structure) {
                return startsForStructure(x,z,s -> s==structure);
            }
        };
    }
    private WorldGenRegion region(int x,int z,java.util.function.BiFunction<Integer,Integer,ProtoChunk> chunk,
                                  Map<BlockPos,BlockState> changes,boolean treeOutput,boolean onDemand) {
        class ScratchRegion extends ScratchWorldGenRegion implements TreeTrunkSink {
            ScratchRegion() {
                super(world,new ChunkPos(x,z),chunk,onDemand);
            }

            @Override public void recordTreeTrunk(BlockPos pos,BlockState state) {
                if(treeOutput && changes!=null && isWithinWriteZone(pos) && getBlockState(pos)==state)
                    changes.put(pos.immutable(),state);
            }

            @Override public Holder<Biome> getNoiseBiome(int qx,int qy,int qz) {
                return treeOutput?getUncachedNoiseBiome(qx,qy,qz):super.getNoiseBiome(qx,qy,qz);
            }

            @Override public Holder<Biome> getUncachedNoiseBiome(int qx,int qy,int qz) {
                return surfaceTerrain.noiseBiome(qx,qy,qz);
            }
            @Override public boolean setBlock(BlockPos pos,BlockState state,int flags,int limit) {
                if(!isWithinWriteZone(pos) || isOutsideBuildHeight(pos)) return false;
                var target=getChunk(pos); var old=target.setBlockState(pos,state,flags);
                // Deliberately bypass WorldGenRegion.setBlock: it updates live-world POIs.
                if(state.hasBlockEntity() && state.getBlock() instanceof EntityBlock block) {
                    var entity=block.newBlockEntity(pos,state); if(entity!=null) target.setBlockEntity(entity);
                } else if(old!=null && old.hasBlockEntity()) target.removeBlockEntity(pos);
                if(changes!=null) {
                    if(!treeOutput || state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.LEAVES)) {
                        changes.put(pos.immutable(),state);
                    } else if(changes.containsKey(pos)) {
                        if(changes.get(pos).is(state.getBlock())) changes.put(pos.immutable(),state);
                        else changes.remove(pos);
                    }
                }
                return true;
            }
            @Override public boolean addFreshEntity(Entity entity) { return false; }
        }
        return new ScratchRegion();
    }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException(); }
}
