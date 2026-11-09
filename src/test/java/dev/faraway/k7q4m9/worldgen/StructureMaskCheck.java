package dev.faraway.k7q4m9.worldgen;

import net.minecraft.world.level.block.Blocks;

/** Check that the optimized structure halo never omits required adjacent chunks. */
public final class StructureMaskCheck {
    public static void run() {
        boolean[][] active={{true,false},{false,false}};
        var terrain=DetachedDecorationSampler.expandedMask(active,1);
        var biomes=DetachedDecorationSampler.expandedMask(terrain,1);
        for(int z=0;z<terrain.length;z++) for(int x=0;x<terrain.length;x++)
            if(terrain[z][x]!=(x<=2 && z<=2)) throw new AssertionError("Structure terrain halo is incomplete or excessive");
        for(int z=0;z<biomes.length;z++) for(int x=0;x<biomes.length;x++)
            if(biomes[z][x]!=(x<=4 && z<=4)) throw new AssertionError("Biome halo does not cover the terrain neighborhood");
        var all=DetachedDecorationSampler.expandedMask(new boolean[][]{{true,true},{true,true}},1);
        for(var row:all) for(boolean b:row) if(!b) throw new AssertionError("Adjacent structure chunks must share a complete halo");
        var patches=new java.util.HashSet<dev.faraway.k7q4m9.terrain.TileKey>();
        StructureIndex.addPatches(patches,new net.minecraft.world.level.levelgen.structure.BoundingBox(-33,0,-1,-32,10,1),16);
        if(!patches.contains(new dev.faraway.k7q4m9.terrain.TileKey(-2,-1,1)) ||
           !patches.contains(new dev.faraway.k7q4m9.terrain.TileKey(-1,0,1)) ||
           patches.contains(new dev.faraway.k7q4m9.terrain.TileKey(1,0,1)))
            throw new AssertionError("Piece patch coverage must include its halo across negative coordinates");
        // After the lake carves air above lava, scanning must stop at lava
        // before reaching the stone floor underneath.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var scan=new NoiseColumns.Scan();
        scan.reset(70);
        if(scan.test(Blocks.AIR.defaultBlockState()) || !scan.test(Blocks.LAVA.defaultBlockState()))
            throw new AssertionError("An exposed lava lake must terminate the terrain scan");
        var lava=scan.column(-64);
        if(lava.topY()!=70 || lava.wet() || !lava.surface().is(Blocks.LAVA))
            throw new AssertionError("Lava height and material must survive without entering the water mesh");
    }
}
