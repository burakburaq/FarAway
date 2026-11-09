package dev.faraway.k7q4m9.worldgen;

import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Phase;
import dev.faraway.k7q4m9.mixin.MaterialRuleContextAccess;
import dev.faraway.k7q4m9.mixin.NoiseGeneratorAccess;
import java.util.Arrays;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;

/** Stock Overworld LOD 2 surface columns, without assembling a temporary chunk. */
final class SelectedSurfaceColumns {
    static final int SPACING=4,SIDE=16/SPACING;
    private static final int NO_WATER=Integer.MIN_VALUE;

    private SelectedSurfaceColumns() {}

    static NoiseColumns.Column[] sample(WorldDefinition world,BiomeManager biomes,WorldGenerationContext generation,ChunkPos pos) {
        if(SharedConstants.DEBUG_AQUIFERS) return null;
        var generator=world.noiseGenerator();
        var settings=generator.generatorSettings().value();
        var noise=settings.noiseSettings().clampToHeightAccessor(world.heights());
        if(noise.height()<=0) return null;
        var volume=new DensityVolume(16,noise.height(),16,pos.getMinBlockX(),noise.minY(),pos.getMinBlockZ());
        var access=(NoiseGeneratorAccess)(Object)generator;
        var noiseChunk=WorldgenProfiler.measure(Phase.NOISE,
            () -> new NoiseChunk(world.randomState(),null,settings,access.faraway$globalFluidPicker().get(),Blender.empty(),volume));
        try(noiseChunk;var density=WorldgenProfiler.measure(Phase.NOISE,
            () -> noiseChunk.cachingSamplers().get(settings.noiseRouter().finalDensity()).sampleVolume(volume))) {
            var terrain=new Terrain(noiseChunk,volume,density,settings.defaultBlock(),biomes);
            var raw=new RawColumn[SIDE*SIDE];
            try(var profile=WorldgenProfiler.scope(Phase.NOISE)) {
                for(int z=0;z<16;z+=SPACING) for(int x=0;x<16;x+=SPACING) {
                    var column=terrain.read(x,z);
                    if(column==null) return null;
                    raw[z/SPACING*SIDE+x/SPACING]=column;
                }
            }
            try(var profile=WorldgenProfiler.scope(Phase.SURFACE)) {
                var materialVolume=materialVolume(volume,raw);
                var context=MaterialRuleContextAccess.faraway$create(world.randomState().surfaceSystem(),
                    world.randomState(),materialVolume,noiseChunk.cachingSamplers(),biomes::getBiome,generation,
                    generator.getBiomeSource().possibleBiomes());
                var updater=(MaterialRuleContextAccess)(Object)context;
                var rule=settings.materialRule().value().compile(context);
                var columns=new NoiseColumns.Column[raw.length];
                for(int i=0;i<raw.length;i++) {
                    int x=i%SIDE*SPACING,z=i/SIDE*SPACING;
                    var column=apply(raw[i],volume.blockX(x),volume.blockZ(z),rule,updater);
                    if(column==null) return null;
                    columns[i]=column;
                }
                return columns;
            }
        }
    }

    private record RawColumn(NoiseColumns.Column column,int gradientX,int gradientZ,int depthBelow,int ruleWaterY) {}

    private static DensityVolume materialVolume(DensityVolume terrain,RawColumn[] columns) {
        int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
        for(var column:columns) if(column.depthBelow()>0) {
            int y=column.column().topY()-1;
            low=Math.min(low,y);
            high=Math.max(high,y);
        }
        if(low>high) low=high=terrain.minBlockY();
        // Ore/material densities prefill this volume at rule compilation.
        return new DensityVolume(16,high-low+1,16,terrain.minBlockX(),low,terrain.minBlockZ());
    }

    private static NoiseColumns.Column apply(RawColumn raw,int x,int z,RuleEvaluator rule,MaterialRuleContextAccess context) {
        var column=raw.column();
        if(raw.depthBelow()==0) return column;
        context.faraway$updateXZ(x,z,raw.gradientX(),raw.gradientZ());
        int y=column.topY()-1;
        context.faraway$updateY(1,raw.depthBelow(),raw.ruleWaterY(),y);
        var material=rule.tryApply(x,y,z);
        if(material==null) return column;
        if(!Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(material) || !material.getFluidState().isEmpty()) return null;
        return new NoiseColumns.Column(column.topY(),column.waterY(),column.wet(),material);
    }

    private static final class Terrain {
        private final NoiseChunk noise;
        private final DensityVolume volume;
        private final DensityBuffer density;
        private final BlockState defaultBlock;
        private final BiomeManager biomes;
        private final int[] tops=new int[256];
        private final NoiseColumns.Scan scan=new NoiseColumns.Scan();
        private final int minY,maxY;

        Terrain(NoiseChunk noise,DensityVolume volume,DensityBuffer density,BlockState defaultBlock,BiomeManager biomes) {
            this.noise=noise; this.volume=volume; this.density=density; this.defaultBlock=defaultBlock; this.biomes=biomes;
            minY=volume.minBlockY(); maxY=minY+volume.sizeY()-1;
            Arrays.fill(tops,Integer.MIN_VALUE);
        }

        RawColumn read(int x,int z) {
            int left=Math.max(0,x-1),right=Math.min(15,x+1),north=Math.max(0,z-1),south=Math.min(15,z+1);
            int top=top(x,z);
            if(special(x,z,top) || special(left,z,top(left,z)) || special(right,z,top(right,z)) ||
                special(x,north,top(x,north)) || special(x,south,top(x,south))) return null;
            int gradientX=top(right,z)-top(left,z),gradientZ=top(x,south)-top(x,north);
            scan.reset(top);
            int ruleWaterY=NO_WATER,depthBelow=0;
            for(int y=top;y>=minY;y--) {
                var state=block(x,y,z);
                if(scan.test(state)) {
                    if(stone(state)) {
                        int below=y-1;
                        while(below>=minY && stone(block(x,below,z))) below--;
                        depthBelow=y-below;
                    }
                    break;
                }
                if(state.isAir()) ruleWaterY=NO_WATER;
                else if(!state.getFluidState().isEmpty() && ruleWaterY==NO_WATER) ruleWaterY=y+1;
                else if(stone(state)) return null;
            }
            return new RawColumn(scan.column(minY),gradientX,gradientZ,depthBelow,ruleWaterY);
        }

        private int top(int x,int z) {
            int index=z*16+x;
            if(tops[index]!=Integer.MIN_VALUE) return tops[index];
            int y=maxY;
            while(y>=minY && block(x,y,z).isAir()) y--;
            return tops[index]=y;
        }

        private boolean special(int x,int z,int top) {
            int wx=volume.blockX(x),wz=volume.blockZ(z);
            return specialBiome(biomes.getBiome(wx,top+1,wz)) || specialBiome(biomes.getBiome(wx,top,wz));
        }

        private static boolean specialBiome(Holder<Biome> biome) {
            return biome.is(Biomes.ERODED_BADLANDS) || biome.is(Biomes.FROZEN_OCEAN) || biome.is(Biomes.DEEP_FROZEN_OCEAN)
                || biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP);
        }

        private BlockState block(int x,int y,int z) {
            var state=noise.aquifer().computeSubstance(volume.blockX(x),y,volume.blockZ(z),
                density.get(volume.indexUnchecked(x,y-minY,z)));
            return state==null?defaultBlock:state;
        }
    }

    private static boolean stone(BlockState state) { return !state.isAir() && state.getFluidState().isEmpty(); }
}
