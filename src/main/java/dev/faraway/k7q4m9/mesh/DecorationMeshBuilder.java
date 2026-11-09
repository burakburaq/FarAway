package dev.faraway.k7q4m9.mesh;

import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.phys.AABB;
import dev.faraway.k7q4m9.terrain.TileKey;

/** Block shapes preserve stairs, slabs and fence silhouettes instead of drawing structure boxes. */
public final class DecorationMeshBuilder {
    public static boolean skipUnsupportedDecoration(BlockState state) {
        var block=state.getBlock();
        return block instanceof net.minecraft.world.level.block.WebBlock
            || block instanceof net.minecraft.world.level.block.AbstractChestBlock<?>
            || block instanceof net.minecraft.world.level.block.CropBlock
            || block instanceof net.minecraft.world.level.block.StemBlock
            || block instanceof net.minecraft.world.level.block.AttachedStemBlock
            || block instanceof net.minecraft.world.level.block.FlowerBlock
            || block instanceof net.minecraft.world.level.block.FlowerBedBlock
            || block instanceof net.minecraft.world.level.block.CactusFlowerBlock
            || block instanceof net.minecraft.world.level.block.TallGrassBlock
            || block instanceof net.minecraft.world.level.block.DryVegetationBlock
            || block instanceof net.minecraft.world.level.block.BushBlock
            || block instanceof net.minecraft.world.level.block.DoublePlantBlock;
    }
    private static final int[][] D={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
    private static final int[][][] F={{{0,0,1},{0,1,1},{0,0,0},{0,1,0}},{{1,0,0},{1,1,0},{1,0,1},{1,1,1}},
        {{0,0,0},{1,0,0},{0,0,1},{1,0,1}},{{0,1,1},{1,1,1},{0,1,0},{1,1,0}},
        {{0,0,0},{0,1,0},{1,0,0},{1,1,0}},{{1,0,1},{1,1,1},{0,0,1},{0,1,1}}};
    private static final int[] TRI={0,1,2,2,1,3};
    public TileMesh build(TileKey key,Map<BlockPos,BlockState> blocks,BooleanSupplier cancelled) {
        return build(key,blocks,(x,z) -> 0x709050,cancelled);
    }
    public TileMesh build(TileKey key,Map<BlockPos,BlockState> blocks,java.util.function.IntBinaryOperator foliageColor,BooleanSupplier cancelled) {
        return build(key,blocks,foliageColor,false,cancelled);
    }
    public TileMesh build(TileKey key,Map<BlockPos,BlockState> blocks,java.util.function.IntBinaryOperator foliageColor,boolean opaqueLeaves,BooleanSupplier cancelled) {
        var out=new ByteArrayOutputStream(); var v=ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        var fullFaces=new GreedyFaceMesher(); var leafFaces=new GreedyFaceMesher();
        int leafStep=Math.min(key.spacing(),4);
        var leaves=new java.util.HashMap<BlockPos,BlockState>();
        for(var entry:blocks.entrySet()) if(TextureMaterial.isLeaf(entry.getValue())) {
            var p=entry.getKey();
            var cell=new BlockPos(Math.floorDiv(p.getX(),leafStep)*leafStep,Math.floorDiv(p.getY(),leafStep)*leafStep,Math.floorDiv(p.getZ(),leafStep)*leafStep);
            leaves.merge(cell,entry.getValue(),(a,b) -> a.getMapColor(EmptyBlockGetter.INSTANCE,cell).col<=b.getMapColor(EmptyBlockGetter.INSTANCE,cell).col?a:b);
        }
        int low=320,high=-64;
        for(var e:blocks.entrySet()) {
            if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            var p=e.getKey(); var state=e.getValue();
            if(TextureMaterial.isLeaf(state) || skipUnsupportedDecoration(state)) continue;
            low=Math.min(low,p.getY()); high=Math.max(high,p.getY()+1);
            int color=state.getMapColor(EmptyBlockGetter.INSTANCE,p).col;
            if(color==0) color=0x709050;
            if(TextureMaterial.block(state)!=0 && TextureMaterial.block(state)!=7) color=0xffffff;
            var boxes=fullBlock(state,key.spacing())?java.util.List.of(new AABB(0,0,0,1,1,1)):state.getShape(EmptyBlockGetter.INSTANCE,p).toAabbs();
            if(boxes.isEmpty()) continue;
            for(AABB box:boxes) for(int f=0;f<6;f++) {
                int[] d=D[f]; var neighbor=blocks.get(p.offset(d[0],d[1],d[2]));
                boolean onEdge=switch(f) {case 0 -> box.minX==0;case 1 -> box.maxX==1;case 2 -> box.minY==0;case 3 -> box.maxY==1;case 4 -> box.minZ==0;default -> box.maxZ==1;};
                if(onEdge && neighbor!=null) {
                    boolean occludes=TextureMaterial.isLeaf(neighbor)
                        ?opaqueLeaves && leafStep==1:neighbor.isSolidRender() || fullBlock(neighbor,key.spacing());
                    if(occludes) continue;
                }
                if(box.minX==0 && box.minY==0 && box.minZ==0 && box.maxX==1 && box.maxY==1 && box.maxZ==1) {
                    fullFaces.add(p.getX(),p.getY(),p.getZ(),f,TextureMaterial.block(state,f)+1,color);
                    continue;
                }
                float shade=f==3?1:f==2?.5f:f<2?.72f:.86f;
                for(int corner:TRI) {
                    var c=F[f][corner]; v.clear();
                    v.putFloat((float)(p.getX()-key.originX()+(c[0]==0?box.minX:box.maxX)))
                        .putFloat((float)(p.getY()+(c[1]==0?box.minY:box.maxY)))
                        .putFloat((float)(p.getZ()-key.originZ()+(c[2]==0?box.minZ:box.maxZ)))
                        .put((byte)Math.round((color>>16&255)*shade)).put((byte)Math.round((color>>8&255)*shade)).put((byte)Math.round((color&255)*shade)).put((byte)(TextureMaterial.block(state,f)+1));
                    out.writeBytes(v.array());
                }
            }
        }
        for(var e:leaves.entrySet()) {
            if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            var p=e.getKey(); int material=TextureMaterial.block(e.getValue());
            int color=material==20?0x619961:material==21?0x80a755:material==26 || material==27?0xffffff:foliageColor.applyAsInt(p.getX(),p.getZ());
            low=Math.min(low,p.getY()); high=Math.max(high,p.getY()+leafStep);
            for(int f=0;f<6;f++) {
                int[] d=D[f]; var adjacent=p.offset(d[0]*leafStep,d[1]*leafStep,d[2]*leafStep);
                if(leaves.containsKey(adjacent)) continue;
                var solid=leafStep==1?blocks.get(adjacent):null;
                if(solid!=null && solid.isSolidRender()) continue;
                leafFaces.add(Math.floorDiv(p.getX(),leafStep),Math.floorDiv(p.getY(),leafStep),Math.floorDiv(p.getZ(),leafStep),f,material+1,color);
            }
        }
        fullFaces.emit(out,1,1,key.originX(),key.originZ(),cancelled);
        leafFaces.emit(out,leafStep,leafStep,key.originX(),key.originZ(),cancelled);
        return new TileMesh(key,out.toByteArray(),new byte[0],new byte[0],low<=high?low:0,low<=high?high:0);
    }
    private static boolean fullBlock(BlockState state,int spacing) {
        return spacing>=2 && (state.getBlock() instanceof StairBlock || state.getBlock() instanceof SlabBlock);
    }
}
