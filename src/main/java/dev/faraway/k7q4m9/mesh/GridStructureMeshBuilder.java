package dev.faraway.k7q4m9.mesh;

import dev.faraway.k7q4m9.terrain.TileKey;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Converts structure blocks into the tile's LOD lattice before building faces. */
public final class GridStructureMeshBuilder {
    private static final int[][] DIRECTIONS={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
    public TileMesh build(TileKey key,Map<BlockPos,BlockState> blocks,IntBinaryOperator foliage,BooleanSupplier cancelled) {
        int step=key.spacing(),vertical=Math.min(step,4);
        var cells=new HashMap<BlockPos,Map<BlockState,Integer>>();
        for(var entry:blocks.entrySet()) {
            if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            var p=entry.getKey(); var state=entry.getValue();
            if(DecorationMeshBuilder.skipUnsupportedDecoration(state)) continue;
            var cell=new BlockPos(Math.floorDiv(p.getX()-key.originX(),step),Math.floorDiv(p.getY(),vertical),Math.floorDiv(p.getZ()-key.originZ(),step));
            if(cell.getX()<0 || cell.getX()>=key.blocks()/key.spacing() || cell.getZ()<0 || cell.getZ()>=key.blocks()/key.spacing()) continue;
            cells.computeIfAbsent(cell,unused -> new HashMap<>()).merge(state,1,Integer::sum);
        }
        var faces=new GreedyFaceMesher(); int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
        for(var entry:cells.entrySet()) {
            if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            var p=entry.getKey();
            var state=entry.getValue().entrySet().stream().sorted(java.util.Comparator
                .<Map.Entry<BlockState,Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(e -> e.getKey().toString())).findFirst().orElseThrow().getKey();
            int material=TextureMaterial.block(state);
            int color=state.is(net.minecraft.tags.BlockTags.LEAVES)?(material==20?0x619961:material==21?0x80a755:material==26 || material==27?0xffffff:foliage.applyAsInt(key.originX()+p.getX()*step,key.originZ()+p.getZ()*step)):
                material!=0 && material!=7?0xffffff:state.getMapColor(EmptyBlockGetter.INSTANCE,BlockPos.ZERO).col;
            if(color==0) color=0x709050;
            low=Math.min(low,p.getY()*vertical); high=Math.max(high,(p.getY()+1)*vertical);
            for(int f=0;f<6;f++) { var d=DIRECTIONS[f]; if(!cells.containsKey(p.offset(d[0],d[1],d[2]))) faces.add(p.getX(),p.getY(),p.getZ(),f,TextureMaterial.block(state,f)+1,color); }
        }
        var out=new ByteArrayOutputStream(); faces.emit(out,step,vertical,0,0,cancelled);
        return new TileMesh(key,out.toByteArray(),new byte[0],new byte[0],low<=high?low:0,low<=high?high:0);
    }
}
