package dev.faraway.k7q4m9.worldgen;

import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Phase;
import dev.faraway.k7q4m9.diagnostics.WorldgenCacheProfile.Kind;
import dev.faraway.k7q4m9.diagnostics.WorldgenCacheProfile.Task;
import dev.faraway.k7q4m9.client.LodSettings.ScratchTerrain;

import dev.faraway.k7q4m9.client.FarAwayClient;
import dev.faraway.k7q4m9.client.LodSettings;
import dev.faraway.k7q4m9.mesh.TextureMaterial;
import dev.faraway.k7q4m9.mixin.NoiseGeneratorAccess;
import dev.faraway.k7q4m9.terrain.TileKey;
import dev.faraway.k7q4m9.terrain.VegetationField;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;

/** Worker-owned surface sampling using the active world's generator. */
public final class WorldTerrainSampler implements SurfaceGridSampler {
    private static final int FULL_SURFACE_SPACING=4;
    // The native surface lake has a footprint smaller than one chunk.
    private static final int LAKE_MAX_SPACING=8;
    private static final int FIRST_SPARSE_SPACING=8;
    private final WorldDefinition world;
    private final Shared shared;
    private final BiomeManager biomes;
    private final BiomeResolver biomeResolver;
    private final Registry<Biome> biomeRegistry;
    private final WorldGenerationContext generationContext;
    private final NoiseColumns noiseColumns;
    private final DensitySamplerSet materialSamplers;
    private final Surface flat;
    private final SurfaceSample empty;
    private static final int BIOME_BLEND_RADIUS=2;
    private final Map<Integer,VegetationField.Profile> profiles=new HashMap<>();
    private final Map<Long,Holder<Biome>> zoomBiomes=new LinkedHashMap<>(1024,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long,Holder<Biome>> entry) { return size()>32768; }
    };
    private final Map<Long,Holder<Biome>> quartBiomes=new LinkedHashMap<>(512,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long,Holder<Biome>> entry) { return size()>8192; }
    };
    private final Map<Long,Tints> tints=new LinkedHashMap<>(1024,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long,Tints> entry) { return size()>8192; }
    };
    private final Map<Long,List<StructureStart>> adaptations=new HashMap<>();
    private final Map<Long,Map<Long,Surface>> lakeColumns=new HashMap<>();
    private final NoiseColumns.Scan blockScan=new NoiseColumns.Scan();
    private boolean adaptTerrain;
    private boolean selectedSurface;
    private ScratchTerrain scratchTerrain=ScratchTerrain.OFF;
    private ChunkKey lastChunkKey;
    private Surface[] lastChunk;
    private long lastSelectedChunk;
    private boolean selectedChunkRead;
    private Surface[] lastSelected;
    private ProtoChunk gradients;
    private int gradientOriginX,gradientOriginZ;

    record Surface(SurfaceSample sample,BlockState state) {}
    record ChunkKey(long chunk,boolean adapted,boolean onDemand) {
        ChunkKey(long chunk,boolean adapted) { this(chunk,adapted,false); }
        @Override public String toString() { return "chunk "+ChunkPos.unpack(chunk)+(adapted?" adapted":"")+(onDemand?" on-demand":""); }
    }
    private record Tints(int grass,int water,int foliage) {}

    public static final class Shared {
        private final SurfaceSampleCache sparse=new SurfaceSampleCache();
        private final ChunkCache<Surface[]> dense=new ChunkCache<>(Kind.DENSE_SURFACE,256);
        private final ChunkCache<Surface[]> selected=new ChunkCache<>(Kind.SELECTED_SURFACE,256);
        private final Supplier<Boolean> selectedSurfaceSupported;
        private final ChunkCache<Set<Holder<Biome>>> vegetationBiomes=new ChunkCache<>(Kind.VEGETATION_BIOMES,2048);
        private final StructureIndex structureIndex;
        private final PalettedContainerFactory containers;
        private final SurfaceLavaLakes lakes;
        private final Supplier<List<FeatureSorter.StepFeatureData>> features;

        public Shared(WorldDefinition world,BooleanSupplier closed) {
            selectedSurfaceSupported=com.google.common.base.Suppliers.memoize(world::stockOverworld);
            structureIndex=world.detachedDecorations()?new StructureIndex(world,closed):null;
            containers=world.detachedDecorations()?PalettedContainerFactory.create(world.registries()):null;
            features=com.google.common.base.Suppliers.memoize(() -> FeatureSorter.buildFeaturesPerStep(
                List.copyOf(world.generator().getBiomeSource().possibleBiomes()),
                biome -> world.generator().getBiomeGenerationSettings(biome).features(),true));
            lakes=world.mode()==WorldDefinition.Mode.NOISE_BASED?new SurfaceLavaLakes(world,closed,features):null;
        }

        public StructureIndex structureIndex() { return structureIndex; }
        public void expire(long now) { sparse.expire(now); }
        public String status() { return sparse.status(); }
    }

    /** Deduplicates in-flight builds and bounds retained snapshots. */
    static final class ChunkCache<V> {
        private final LinkedHashMap<ChunkKey,Task<V>> entries=new LinkedHashMap<>(64,.75f,true);
        private final Kind kind;
        private final int limit;
        ChunkCache(Kind kind,int limit) { this.kind=kind; this.limit=limit; }

        V get(ChunkKey key,Callable<V> build) {
            Task<V> task;
            synchronized(this) {
                task=entries.computeIfAbsent(key,ignored -> new Task<>(kind,build));
            }
            try {
                V result=task.read(key);
                synchronized(this) {
                    var it=entries.entrySet().iterator();
                    while(entries.size()>limit && it.hasNext()) if(it.next().getValue().isDone()) it.remove();
                }
                return result;
            } catch(InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            } catch(ExecutionException failure) {
                synchronized(this) { entries.remove(key,task); }
                if(failure.getCause() instanceof RuntimeException runtime) throw runtime;
                if(failure.getCause() instanceof Error error) throw error;
                throw new IllegalStateException("Surface chunk sampling failed",failure.getCause());
            }
        }

    }

    public WorldTerrainSampler(WorldDefinition world,Shared shared) {
        this.world=world;
        this.shared=shared;
        selectedSurface=LodSettings.get().generation().selectedSurface() && shared.selectedSurfaceSupported.get();
        biomeResolver=world.mode()==WorldDefinition.Mode.LOADED_CHUNKS?null:world.generator().getBiomeSource().createUncachedResolver(world.randomState());
        biomes=biomeResolver==null?null:new BiomeManager(this::noiseBiome,BiomeManager.obfuscateSeed(world.seed()));
        biomeRegistry=world.registries().lookupOrThrow(Registries.BIOME);
        boolean noise=world.mode()==WorldDefinition.Mode.NOISE_BASED;
        generationContext=noise?new WorldGenerationContext(world.generator(),world.heights()):null;
        noiseColumns=noise?new NoiseColumns(world):null;
        materialSamplers=noise?world.randomState().samplersWithContext(SamplerContext.builder().enableCaches().build()):null;
        empty=new SurfaceSample((short)minY(),(short)0,0,0,0,false,false,0,0);
        if(world.mode()==WorldDefinition.Mode.SUPERFLAT) {
            var column=world.generator().getBaseColumn(0,0,world.heights(),world.randomState());
            flat=describe(0,0,scanBlocks(maxY(),column::getBlock),false,null);
        } else flat=null;
    }

    public void prepare(boolean structures) {
        prepare(structures,LodSettings.get().generation());
    }

    public void prepare(boolean structures,ScratchTerrain scratchTerrain) {
        prepare(structures,scratchTerrain,LodSettings.get().generation().selectedSurface());
    }

    public void prepare(boolean structures,LodSettings.GenerationOptions options) {
        prepare(structures,options.scratchTerrain(),options.selectedSurface());
    }

    private void prepare(boolean structures,ScratchTerrain scratchTerrain,boolean selectedSurface) {
        adaptTerrain=structures && noiseColumns!=null;
        this.scratchTerrain=scratchTerrain;
        this.selectedSurface=selectedSurface && shared.selectedSurfaceSupported.get();
        adaptations.clear();
        lakeColumns.clear();
    }

    boolean terrainAdapted() { return adaptTerrain; }
    public ScratchTerrain scratchTerrainMode() { return scratchTerrain; }

    public static boolean resumableSurface(WorldDefinition world,int spacing) {
        return world.mode()==WorldDefinition.Mode.NOISE_BASED && spacing<=FULL_SURFACE_SPACING
            && (spacing<2 || !world.gpuSamplingCompatible() || !LodSettings.get().gpuSurfaceSampling());
    }
    List<FeatureSorter.StepFeatureData> featureSteps() { return shared.features.get(); }

    Set<Holder<Biome>> vegetationBiomes(ChunkPos pos) {
        return shared.vegetationBiomes.get(new ChunkKey(pos.pack(),false),() -> {
            var chunk=new ProtoChunk(pos,UpgradeData.EMPTY,world.heights(),shared.containers,null);
            WorldgenProfiler.await(() -> world.generator().createBiomes(world.randomState(),Blender.empty(),null,chunk).join());
            var present=new java.util.HashSet<Holder<Biome>>();
            for(var section:chunk.getSections()) section.getBiomes().getAll(present::add);
            present.retainAll(world.generator().getBiomeSource().possibleBiomes());
            return Set.copyOf(present);
        });
    }

    @Override public long seed() { return world.seed(); }
    @Override public int seaLevel() { return world.generator().getSeaLevel(); }
    @Override public int minY() { return world.heights().getMinY(); }
    @Override public int maxY() { return world.heights().getMaxY(); }

    @Override public VegetationField.Profile vegetationProfile(int biomeId) {
        if(!world.detachedDecorations()) return VegetationProfiles.EMPTY;
        return profiles.computeIfAbsent(biomeId,id -> VegetationProfiles.profile(world,id));
    }

    @Override public SurfaceSample[] sampleRegion(int ox,int oz,int width,int depth,int spacing,BooleanSupplier cancelled) {
        if(width<1 || depth<1 || spacing<1) throw new IllegalArgumentException("Invalid surface grid");
        if(world.mode()==WorldDefinition.Mode.LOADED_CHUNKS) return loadedRegion(ox,oz,width,depth,spacing,cancelled);
        var result=new SurfaceSample[Math.multiplyExact(width,depth)];
        if(flat!=null) {
            check(cancelled);
            Arrays.fill(result,flat.sample());
            return result;
        }
        if(spacing>=2 && world.gpuSamplingCompatible() && LodSettings.get().gpuSurfaceSampling()
            && sampleGpu(result,ox,oz,width,depth,spacing,cancelled)) return result;

        boolean batched=spacing>FULL_SURFACE_SPACING && spacing<=16;
        int side=batched?4:1;
        for(int z=0;z<depth;z+=side) for(int x=0;x<width;x+=side) {
            check(cancelled);
            if(batched) fillBatch(result,ox,oz,width,x,z,Math.min(side,width-x),Math.min(side,depth-z),spacing,cancelled);
            else result[z*width+x]=sample(ox+x*spacing,oz+z*spacing,spacing);
        }
        return result;
    }

    private void fillBatch(SurfaceSample[] output,int ox,int oz,int stride,int x0,int z0,int width,int depth,int spacing,BooleanSupplier cancelled) {
        var needed=new boolean[width*depth];
        boolean missing=false;
        for(int z=0;z<depth;z++) for(int x=0;x<width;x++) {
            int wx=ox+(x0+x)*spacing,wz=oz+(z0+z)*spacing;
            var base=knownSurface(wx,wz,spacing);
            if(base==null) {
                needed[z*width+x]=true;
                missing=true;
            } else output[(z0+z)*stride+x0+x]=blend(wx,wz,spacing,base);
        }
        if(!missing) return;
        var columns=WorldgenProfiler.measure(Phase.NOISE,() -> noiseColumns.batch(ox+x0*spacing,oz+z0*spacing,width,depth,spacing,needed,cancelled));
        for(int z=0;z<depth;z++) for(int x=0;x<width;x++) {
            var column=columns[z*width+x];
            if(column==null) continue;
            int wx=ox+(x0+x)*spacing,wz=oz+(z0+z)*spacing;
            output[(z0+z)*stride+x0+x]=blend(wx,wz,spacing,cacheSparse(wx,wz,column));
        }
    }

    @Override public SurfaceSample sample(int x,int z,int spacing) {
        if(flat!=null) return flat.sample();
        if(noiseColumns==null) return loadedRegion(x,z,1,1,spacing,()->false)[0];
        var base=knownSurface(x,z,spacing);
        if(base==null) base=cacheSparse(x,z,scanNoise(x,z));
        return blend(x,z,spacing,base);
    }

    private SurfaceSample knownSurface(int x,int z,int spacing) {
        long key=ChunkPos.pack(x>>4,z>>4);
        var starts=adaptations(key,adaptTerrain);
        if(spacing<=LAKE_MAX_SPACING) {
            var lake=lakeSurface(x,z,key);
            if(lake!=null) return lake.sample();
        }
        if(spacing==SelectedSurfaceColumns.SPACING && selectedSurface && starts.isEmpty() && ((x|z)&3)==0) {
            var selected=selected(x,z,key);
            if(selected!=null) return selected.sample();
        }
        if(spacing<=FULL_SURFACE_SPACING || !starts.isEmpty()) return dense(x,z,key,starts).sample();
        return shared.sparse.get(SurfaceSampleCache.key(x,z));
    }

    private SurfaceSample cacheSparse(int x,int z,NoiseColumns.Column column) {
        var sample=describe(x,z,column,true,null).sample();
        shared.sparse.put(SurfaceSampleCache.key(x,z),sample);
        return sample;
    }

    @Override public SurfaceSample placementSurface(int x,int z) { return placementColumn(x,z).sample(); }

    Surface placementColumn(int x,int z) {
        if(flat!=null) return flat;
        long key=ChunkPos.pack(x>>4,z>>4);
        var lake=lakeSurface(x,z,key);
        return lake!=null?lake:dense(x,z,key,adaptations(key,adaptTerrain));
    }

    Holder<Biome> noiseBiome(int x,int y,int z) {
        return quartBiomes.computeIfAbsent(BlockPos.asLong(x,y,z),ignored -> biomeResolver.getNoiseBiome(x,y,z));
    }

    @Override public SurfaceSample vegetationSurface(int x,int z,int spacing) {
        if(flat!=null) return flat.sample();
        int terrainSpacing=Math.max(spacing,FIRST_SPARSE_SPACING);
        var base=knownSurface(x,z,terrainSpacing);
        if(base==null) base=cacheSparse(x,z,scanNoise(x,z));
        return blend(x,z,spacing,base);
    }

    private NoiseColumns.Column scanNoise(int x,int z) {
        try(var profile=WorldgenProfiler.scope(Phase.NOISE)) { return noiseColumns.scan(x,z); }
    }

    private Surface lakeSurface(int x,int z,long chunk) {
        var columns=lakeColumns.computeIfAbsent(chunk,key -> shared.lakes.columnsInChunk(
            ChunkPos.unpack(key),adaptTerrain,scratchTerrain.lavaLakes(),this::buildRawChunk,this::readChunkSurface));
        return columns.isEmpty()?null:columns.get(SurfaceSampleCache.key(x,z));
    }

    private List<StructureStart> adaptations(long chunk,boolean enabled) {
        if(!enabled) return List.of();
        return adaptations.computeIfAbsent(chunk,key -> WorldgenProfiler.measure(Phase.STRUCTURE_STARTS,() -> {
            var pos=ChunkPos.unpack(key);
            return shared.structureIndex.get(new TileKey(pos.x()>>1,pos.z()>>1,1)).starts().stream()
                .map(StructureSampler.Found::start)
                .filter(start -> start.getStructure().terrainAdaptation()!=TerrainAdjustment.NONE &&
                    start.getBoundingBox().intersects(pos.getMinBlockX(),pos.getMinBlockZ(),pos.getMaxBlockX(),pos.getMaxBlockZ()))
                .toList();
        }));
    }

    private Surface dense(int x,int z,long chunk,List<StructureStart> starts) {
        var key=new ChunkKey(chunk,!starts.isEmpty());
        if(!key.equals(lastChunkKey)) {
            lastChunk=shared.dense.get(key,() -> {
                var pos=ChunkPos.unpack(chunk);
                var raw=buildRawChunk(pos,starts);
                var surfaces=new Surface[256];
                for(int dz=0;dz<16;dz++) for(int dx=0;dx<16;dx++)
                    surfaces[dz*16+dx]=readChunkSurface(raw,pos.getMinBlockX()+dx,pos.getMinBlockZ()+dz,Heightmap.Types.WORLD_SURFACE_WG);
                return surfaces;
            });
            lastChunkKey=key;
        }
        return lastChunk[(z&15)*16+(x&15)];
    }

    private Surface selected(int x,int z,long chunk) {
        if(!selectedChunkRead || chunk!=lastSelectedChunk) {
            lastSelected=shared.selected.get(new ChunkKey(chunk,false),() -> {
                var pos=ChunkPos.unpack(chunk);
                var columns=SelectedSurfaceColumns.sample(world,biomes,generationContext,pos);
                if(columns==null) return null;
                var surfaces=new Surface[columns.length];
                for(int i=0;i<columns.length;i++) {
                    int wx=pos.getMinBlockX()+i%SelectedSurfaceColumns.SIDE*SelectedSurfaceColumns.SPACING;
                    int wz=pos.getMinBlockZ()+i/SelectedSurfaceColumns.SIDE*SelectedSurfaceColumns.SPACING;
                    surfaces[i]=describe(wx,wz,columns[i],false,null);
                }
                return surfaces;
            });
            lastSelectedChunk=chunk;
            selectedChunkRead=true;
        }
        int ix=(x&15)/SelectedSurfaceColumns.SPACING,iz=(z&15)/SelectedSurfaceColumns.SPACING;
        return lastSelected==null?null:lastSelected[iz*SelectedSurfaceColumns.SIDE+ix];
    }

    private ProtoChunk buildRawChunk(ChunkPos pos,boolean adapted) {
        return buildRawChunk(pos,adaptations(pos.pack(),adapted));
    }

    private ProtoChunk buildRawChunk(ChunkPos pos,List<StructureStart> starts) {
        var generator=world.noiseGenerator();
        var settings=generator.generatorSettings().value();
        var noise=settings.noiseSettings().clampToHeightAccessor(world.heights());
        var chunk=new ProtoChunk(pos,UpgradeData.EMPTY,world.heights(),shared.containers,null);
        if(noise.height()>0) {
            var access=(NoiseGeneratorAccess)(Object)generator;
            var volume=new DensityVolume(16,noise.height(),16,pos.getMinBlockX(),noise.minY(),pos.getMinBlockZ());
            Beardifier beardifier=null;
            if(!starts.isEmpty()) {
                var manager=new StructureManager(world.sourceLevel(),new WorldOptions(world.seed(),true,false),null) {
                    @Override public List<StructureStart> startsForStructure(int x,int z,Predicate<Structure> predicate) {
                        return starts.stream().filter(start -> predicate.test(start.getStructure())).toList();
                    }
                };
                beardifier=Beardifier.forStructuresInChunk(manager,pos);
            }
            NoiseChunk density;
            try(var profile=WorldgenProfiler.scope(Phase.NOISE)) {
                density=new NoiseChunk(world.randomState(),beardifier,settings,access.faraway$globalFluidPicker().get(),Blender.empty(),volume);
            }
            try(density) {
                access.faraway$fillNoise(density,chunk);
                try(var profile=WorldgenProfiler.scope(Phase.SURFACE)) {
                    world.randomState().surfaceSystem().buildSurface(world.randomState(),biomes,generationContext,chunk,density,
                        settings.materialRule().value(),generator.getBiomeSource().possibleBiomes());
                }
            }
        }
        chunk.setPersistedStatus(ChunkStatus.TERRAIN);
        return chunk;
    }

    private Surface readChunkSurface(ProtoChunk chunk,int x,int z) {
        // WORLD_SURFACE is updated by writes during the feature stage.
        return readChunkSurface(chunk,x,z,Heightmap.Types.WORLD_SURFACE);
    }

    private Surface readChunkSurface(ProtoChunk chunk,int x,int z,Heightmap.Types heightmap) {
        var cursor=new BlockPos.MutableBlockPos();
        int y=chunk.getHeight(heightmap,x&15,z&15);
        return describe(x,z,scanBlocks(y,height -> chunk.getBlockState(cursor.set(x,height,z))),false,null);
    }

    private NoiseColumns.Column scanBlocks(int startY,IntFunction<BlockState> block) {
        blockScan.reset(startY);
        for(int y=startY;y>=minY();y--) if(blockScan.test(block.apply(y))) break;
        return blockScan.column(minY());
    }

    private Surface describe(int x,int z,NoiseColumns.Column column,boolean applyRule,LevelChunk loaded) {
        int top=column.topY();
        BlockState surface=column.surface()==null?Blocks.AIR.defaultBlockState():column.surface();
        if(applyRule && surface==world.noiseGenerator().generatorSettings().value().defaultBlock()) {
            try(var profile=WorldgenProfiler.scope(Phase.SURFACE)) {
                var rule=topMaterial(x,top-1,z,column.wet());
                if(!rule.isAir()) surface=rule;
            }
        }
        try(var profile=WorldgenProfiler.scope(Phase.BIOME_COLOR)) {
            return colorSurface(x,z,column,loaded,top,surface);
        }
    }

    private Surface colorSurface(int x,int z,NoiseColumns.Column column,LevelChunk loaded,int top,BlockState surface) {
        var pos=new BlockPos(x,top-1,z);
        var biome=(loaded==null?biomeAt(x,top-1,z):loaded.getNoiseBiome(x>>2,(top-1)>>2,z>>2)).value();
        int biomeId=biomeRegistry.getId(biome);
        int color=surface.getMapColor(EmptyBlockGetter.INSTANCE,pos).col;
        boolean grass=surface.is(Blocks.GRASS_BLOCK);
        if(grass) color=biome.getGrassColor(x,z);
        if(color==0 && !surface.isAir()) color=0x808080;
        int material=surface.isAir() || grass?0:NoiseColumns.isLava(surface)?
            TextureMaterial.lavaSurface():TextureMaterial.block(surface);
        return new Surface(new SurfaceSample(checkedY(top),checkedY(column.waterY()),biomeId,color,
            biome.getWaterColor(),!surface.isAir(),column.wet() && column.waterY()>top,material,biome.getFoliageColor()),surface);
    }

    private Holder<Biome> biomeAt(int x,int y,int z) {
        return zoomBiomes.computeIfAbsent(BlockPos.asLong(x,y,z),ignored -> biomes.getBiome(x,y,z));
    }

    private SurfaceSample blend(int x,int z,int spacing,SurfaceSample sample) {
        if(!LodSettings.blendEnabled(spacing)) return sample;
        try(var profile=WorldgenProfiler.scope(Phase.BIOME_COLOR)) {
            int y=sample.terrainTopY()-1;
            var tint=tints.computeIfAbsent(BlockPos.asLong(x,y,z),ignored -> blendedTints(x,y,z));
            return new SurfaceSample(sample.terrainTopY(),sample.waterTopY(),sample.biomeId(),
                sample.terrain() && sample.material()==0?tint.grass():sample.color(),tint.water(),sample.terrain(),sample.water(),sample.material(),tint.foliage());
        }
    }

    private Tints blendedTints(int x,int y,int z) {
        int r=0,g=0,b=0,wr=0,wg=0,wb=0,fr=0,fg=0,fb=0;
        for(int dz=-BIOME_BLEND_RADIUS;dz<=BIOME_BLEND_RADIUS;dz++) for(int dx=-BIOME_BLEND_RADIUS;dx<=BIOME_BLEND_RADIUS;dx++) {
            var biome=biomeAt(x+dx,y,z+dz).value();
            int grass=biome.getGrassColor(x+dx,z+dz),water=biome.getWaterColor(),foliage=biome.getFoliageColor();
            r+=grass>>16&255; g+=grass>>8&255; b+=grass&255;
            wr+=water>>16&255; wg+=water>>8&255; wb+=water&255;
            fr+=foliage>>16&255; fg+=foliage>>8&255; fb+=foliage&255;
        }
        int side=2*BIOME_BLEND_RADIUS+1,count=side*side;
        return new Tints((r/count<<16)|(g/count<<8)|b/count,(wr/count<<16)|(wg/count<<8)|wb/count,(fr/count<<16)|(fg/count<<8)|fb/count);
    }

    private BlockState topMaterial(int x,int y,int z,boolean waterAbove) {
        if(gradients==null) {
            gradients=new ProtoChunk(ChunkPos.ZERO,UpgradeData.EMPTY,world.heights(),shared.containers,null) {
                @Override public int getHeight(Heightmap.Types type,int localX,int localZ) {
                    // Sparse material rules need a local slope estimate, not four
                    // more full aquifer/terrain scans per output sample.
                    var preliminary=world.randomState().surfaceSystem().preliminarySurfaceFunction();
                    return (int)Math.floor(materialSamplers.sampleValue(preliminary,gradientOriginX+localX,0,gradientOriginZ+localZ));
                }
            };
        }
        gradientOriginX=x&~15;
        gradientOriginZ=z&~15;
        var settings=world.noiseGenerator().generatorSettings().value();
        return world.randomState().surfaceSystem().topMaterial(settings.materialRule().value(),world.randomState(),
            generationContext,biomes::getBiome,gradients,materialSamplers,new BlockPos(x,y,z),waterAbove).orElse(settings.defaultBlock());
    }

    private boolean sampleGpu(SurfaceSample[] result,int ox,int oz,int width,int depth,int spacing,BooleanSupplier cancelled) {
        long started=System.nanoTime();
        var measured=WorldgenProfiler.measure(Phase.GPU_WAIT,() ->
            GpuSurfaceSampler.sample(ox,oz,width,depth,spacing,world.seed(),minY(),maxY(),seaLevel(),cancelled));
        if(measured==null) return false;
        long metadataStart=System.nanoTime();
        try(var profile=WorldgenProfiler.scope(Phase.BIOME_COLOR)) {
            for(int z=0;z<depth;z++) for(int x=0;x<width;x++) {
                check(cancelled);
                int wx=ox+x*spacing,wz=oz+z*spacing,top=measured.heights()[z*width+x];
                boolean wet=top<seaLevel();
                var biome=biomeAt(wx,top-1,wz);
                var name=biome.unwrapKey().orElseThrow().identifier().getPath();
                int color=biome.value().getGrassColor(wx,wz);
                if(name.contains("desert") || name.contains("beach")) color=0xd8c38a;
                else if(name.contains("badlands")) color=0xb86b40;
                else if(biome.value().getBaseTemperature()<.15f) color=0xe7edf0;
                else if(name.contains("peak") || name.contains("stony")) color=0x92928c;
                var sample=new SurfaceSample(checkedY(top),checkedY(wet?seaLevel():0),biomeRegistry.getId(biome.value()),color,
                    biome.value().getWaterColor(),true,wet,TextureMaterial.terrain(color),biome.value().getFoliageColor());
                result[z*width+x]=blend(wx,wz,spacing,sample);
            }
        }
        long finished=System.nanoTime();
        FarAwayClient.LOGGER.info("FarAway GPU surface timing lod={} originX={} originZ={} samples={} queueMs={} submitMs={} completionWaitMs={} gpuMs={} readbackMs={} requestMs={} workerWaitMs={} biomeMetadataMs={} totalMs={}",
            Integer.numberOfTrailingZeros(spacing),ox,oz,result.length,measured.queueNs()/1e6,measured.submitNs()/1e6,
            measured.completionWaitNs()/1e6,measured.gpuNs()/1e6,measured.readbackNs()/1e6,measured.totalNs()/1e6,
            (metadataStart-started)/1e6,(finished-metadataStart)/1e6,(finished-started)/1e6);
        return true;
    }

    private SurfaceSample[] loadedRegion(int ox,int oz,int width,int depth,int spacing,BooleanSupplier cancelled) {
        var level=world.sourceLevel();
        try {
            var work=WorldgenProfiler.activeWork();
            return WorldgenProfiler.await(() -> level.getServer().submit(() -> {
                try(var profile=WorldgenProfiler.scope(work,Phase.LOADED_SURFACE)) {
                    var result=new SurfaceSample[width*depth];
                    var cursor=new BlockPos.MutableBlockPos();
                    var scan=new NoiseColumns.Scan();
                    for(int z=0;z<depth;z++) for(int x=0;x<width;x++) {
                        check(cancelled);
                        int wx=ox+x*spacing,wz=oz+z*spacing;
                        var chunk=level.getChunkSource().getChunkNow(wx>>4,wz>>4);
                        if(chunk==null) {
                            result[z*width+x]=empty;
                            continue;
                        }
                        int y=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,wx&15,wz&15);
                        scan.reset(y);
                        for(;y>=minY();y--) if(scan.test(chunk.getBlockState(cursor.set(wx,y,wz)))) break;
                        result[z*width+x]=describe(wx,wz,scan.column(minY()),false,chunk).sample();
                    }
                    return result;
                }
            }).join());
        } catch(CompletionException failed) {
            if(failed.getCause() instanceof CancellationException stopped) throw stopped;
            throw failed;
        }
    }

    private static short checkedY(int y) {
        if(y<Short.MIN_VALUE || y>Short.MAX_VALUE) throw new IllegalArgumentException("Height outside storage range");
        return (short)y;
    }

    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean()) throw new CancellationException(); }
}
