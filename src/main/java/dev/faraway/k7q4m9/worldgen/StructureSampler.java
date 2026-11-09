package dev.faraway.k7q4m9.worldgen;

import dev.faraway.k7q4m9.diagnostics.WorldgenCacheProfile.Future;
import dev.faraway.k7q4m9.diagnostics.WorldgenCacheProfile.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

/** Worker-owned structure discovery. Does not load chunks or place blocks in a live world. */
public final class StructureSampler {
    public static final int RADIUS_BLOCKS=256*16;
    public record Found(String type,ChunkPos startChunk,StructureStart start) {}
    private final WorldDefinition world;
    private final RandomState random;
    private final ChunkGeneratorStructureState state;
    private final CandidateCache candidateCache;
    public static final class CandidateCache {
        private record Key(net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.structure.StructureSet> set,long chunk) {
            @Override public String toString() { return set.identifier()+" chunk "+ChunkPos.unpack(chunk); }
        }
        private final java.util.concurrent.ConcurrentHashMap<Key,Future<java.util.Optional<Found>>> entries=new java.util.concurrent.ConcurrentHashMap<>();
        private java.util.Optional<Found> get(Key key,java.util.function.Supplier<java.util.Optional<Found>> generator) {
            var created=new Future<java.util.Optional<Found>>();
            var previous=entries.putIfAbsent(key,created); var future=previous==null?created:previous;
            if(previous==null) {
                try {
                    java.util.Optional<Found> result;
                    try(var guard=CachePause.producing();var profile=created.build(Kind.STRUCTURE_CANDIDATE)) { result=generator.get(); }
                    created.complete(result);
                }
                catch(Throwable failure) { entries.remove(key,created); created.completeExceptionally(failure); }
                if(entries.size()>8192) entries.entrySet().removeIf(e -> e.getValue().isDone() && e.getKey().hashCode()%4==0);
            }
            try { return previous==null?future.join():future.read(Kind.STRUCTURE_CANDIDATE,key); }
            catch(java.util.concurrent.CompletionException failure) {
                if(failure.getCause() instanceof RuntimeException runtime) throw runtime;
                throw failure;
            }
        }
    }
    public StructureSampler(WorldDefinition world) {
        this(world,new CandidateCache());
    }
    public StructureSampler(WorldDefinition world,CandidateCache candidateCache) {
        this.world=world;
        this.candidateCache=candidateCache;
        random=world.randomState();
        state=world.sourceLevel().getChunkSource().getGeneratorState();
    }
    public static boolean supported(String name) {
        return name.startsWith("village_") || name.startsWith("abandoned_camp_") ||
            (name.startsWith("ruined_portal") && !name.equals("ruined_portal_nether")) ||
            java.util.Set.of("mansion","pillager_outpost","desert_pyramid","jungle_pyramid","swamp_hut","igloo").contains(name);
    }
    private boolean supported(Holder<Structure> holder) {
        var id=holder.unwrapKey().orElseThrow().identifier();
        if(id.getNamespace().equals("minecraft")) return supported(id.getPath());
        return holder.value() instanceof JigsawStructure &&
            holder.value().step()==GenerationStep.Decoration.SURFACE_STRUCTURES;
    }

    private boolean nearSurface(Holder<Structure> holder,StructureStart start) {
        if(holder.unwrapKey().orElseThrow().identifier().getNamespace().equals("minecraft")) return true;
        var anchor=start.getPieces().getFirst().getBoundingBox();
        var center=anchor.getCenter();
        int y=world.generator().getBaseHeight(center.getX(),center.getZ(),Heightmap.Types.WORLD_SURFACE_WG,world.heights(),random);
        return anchor.maxY()>=y-8 && anchor.minY()<=y+16;
    }

    public List<Found> discover(int cameraX,int cameraZ,BooleanSupplier cancelled) {
        return discover(cameraX,cameraZ,RADIUS_BLOCKS,cancelled);
    }
    public List<Found> discover(int cameraX,int cameraZ,int radius,BooleanSupplier cancelled) {
        var found=new ArrayList<Found>();
        var climate=random.createClimateSampler(SamplerContext.builder().enableCaches().build());
        int minX=Math.floorDiv(cameraX-radius,16)-64,maxX=Math.floorDiv(cameraX+radius,16)+64;
        int minZ=Math.floorDiv(cameraZ-radius,16)-64,maxZ=Math.floorDiv(cameraZ+radius,16)+64;
        for(var set:state.possibleStructureSets()) {
            if(!(set.value().placement() instanceof RandomSpreadStructurePlacement placement)) continue;
            if(set.value().structures().stream().noneMatch(e -> supported(e.structure()))) continue;
            int spacing=placement.spacing();
            for(int gz=Math.floorDiv(minZ,spacing);gz<=Math.floorDiv(maxZ,spacing);gz++) for(int gx=Math.floorDiv(minX,spacing);gx<=Math.floorDiv(maxX,spacing);gx++) {
                if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                var pos=placement.getPotentialStructureChunk(state.getLevelSeed(),gx*spacing,gz*spacing);
                if(!placement.isStructureChunk(state,pos.x(),pos.z())) continue;
                var key=new CandidateCache.Key(set.unwrapKey().orElseThrow(),ChunkPos.pack(pos.x(),pos.z()));
                var candidate=candidateCache.get(key,() -> generateCandidate(set.value(),pos,climate,cancelled));
                if(candidate.isEmpty()) continue;
                var result=candidate.get(); var bounds=result.start().getBoundingBox();
                double dx=Math.max(0,Math.max(bounds.minX()-cameraX,cameraX-bounds.maxX()));
                double dz=Math.max(0,Math.max(bounds.minZ()-cameraZ,cameraZ-bounds.maxZ()));
                if(dx*dx+dz*dz<=(double)radius*radius) found.add(result);
            }
        }
        found.sort(java.util.Comparator.comparingDouble(f -> Math.hypot(f.startChunk().getMiddleBlockX()-cameraX,f.startChunk().getMiddleBlockZ()-cameraZ)));
        return List.copyOf(found);
    }
    private java.util.Optional<Found> generateCandidate(net.minecraft.world.level.levelgen.structure.StructureSet set,ChunkPos pos,
            net.minecraft.world.level.biome.Climate.Sampler climate,BooleanSupplier cancelled) {
                if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                var options=new ArrayList<>(set.structures());
                var rng=new WorldgenRandom(new LegacyRandomSource(0)); rng.setLargeFeatureSeed(state.getLevelSeed(),pos.x(),pos.z());
                int total=options.stream().mapToInt(e -> e.weight()).sum();
                while(!options.isEmpty()) {
                    int roll=rng.nextInt(total),index=0;
                    while((roll-=options.get(index).weight())>=0) index++;
                    var selected=options.remove(index); total-=selected.weight();
                    var structure=selected.structure().value();
                    var start=structure.generate(selected.structure(),world.sourceLevel().dimension(),world.registries(),world.generator(),world.generator().getBiomeSource(),climate,random,world.templates(),state.getLevelSeed(),pos,0,world.heights(),structure.biomes()::contains);
                    if(!start.isValid()) continue;
                    String type=selected.structure().unwrapKey().orElseThrow().identifier().getPath();
                    return supported(selected.structure()) && nearSurface(selected.structure(),start)?java.util.Optional.of(new Found(type,pos,start)):java.util.Optional.empty();
                }
                return java.util.Optional.empty();
    }
}
