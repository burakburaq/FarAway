package dev.faraway.k7q4m9.render;

import dev.faraway.k7q4m9.mesh.TextureMaterial;
import dev.faraway.k7q4m9.mesh.VoxelSurface;
import dev.faraway.k7q4m9.terrain.TileKey;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Vertical joins at the outside perimeter of the currently drawable vanilla chunks. */
final class VanillaSeamMesh {
    private static final int SURFACE_DEPTH=16;
    private final List<VanillaCoverage.Edge> pending;
    private final LongOpenHashSet patchedChunks=new LongOpenHashSet();
    private final int originX,originZ;
    private final int minOutsideX,minOutsideZ,maxOutsideX,maxOutsideZ;
    private final ByteArrayOutputStream out=new ByteArrayOutputStream();
    private final ByteBuffer vertex=ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());

    VanillaSeamMesh(List<VanillaCoverage.Edge> edges) {
        pending=new ArrayList<>(edges);
        originX=edges.isEmpty()?0:edges.getFirst().x()*16;
        originZ=edges.isEmpty()?0:edges.getFirst().z()*16;
        int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(var edge:edges) {
            int x=(edge.x()+edge.dx())*16,z=(edge.z()+edge.dz())*16;
            minX=Math.min(minX,x); maxX=Math.max(maxX,x);
            minZ=Math.min(minZ,z); maxZ=Math.max(maxZ,z);
        }
        minOutsideX=minX;minOutsideZ=minZ;maxOutsideX=maxX;maxOutsideZ=maxZ;
    }

    boolean intersects(TileKey key) {
        return key.originX()<=maxOutsideX && key.originZ()<=maxOutsideZ
            && key.originX()+key.blocks()>minOutsideX && key.originZ()+key.blocks()>minOutsideZ;
    }

    int originX() { return originX; }
    int originZ() { return originZ; }
    ByteBuffer data() { return ByteBuffer.wrap(out.toByteArray()); }
    LongOpenHashSet patchedChunks() { return patchedChunks; }

    void addTile(TileKey key,VoxelSurface surface,boolean colored) {
        int minX=key.originX(),minZ=key.originZ();
        int maxX=minX+key.blocks(),maxZ=minZ+key.blocks();
        for(var it=pending.iterator();it.hasNext();) {
            var edge=it.next();
            int outsideX=(edge.x()+edge.dx())*16,outsideZ=(edge.z()+edge.dz())*16;
            if(outsideX<minX || outsideX>=maxX || outsideZ<minZ || outsideZ>=maxZ) continue;
            patchedChunks.add(ChunkPos.pack(edge.x()+edge.dx(),edge.z()+edge.dz()));
            int startX=edge.x()*16+(edge.dx()>0?16:0);
            int startZ=edge.z()*16+(edge.dz()>0?16:0);
            int stepX=Math.abs(edge.dz()),stepZ=Math.abs(edge.dx());
            var cursor=new BlockPos.MutableBlockPos();
            int debugColor=colored?LodColors.forSpacing(key.spacing()):-1;
            int faceIndex=switch(edge.face()) { case WEST -> 0; case EAST -> 1; case NORTH -> 4; default -> 5; };
            for(int i=0;i<16;i++) {
                int x=startX+stepX*i,z=startZ+stepZ*i;
                int sampleX=x+(edge.dx()<0?-1:0),sampleZ=z+(edge.dz()<0?-1:0);
                int along=i;
                int lodAppearance=surface.appearance(sampleX,sampleZ);
                boolean water=(lodAppearance>>>24)==0;
                int lodY=surface.height(sampleX,sampleZ);
                addColumn(x,z,edge.face(),lodY,edge.height(i),
                    edge.chunk().getMinY(),water,lodAppearance,debugColor,y -> {
                        var state=edge.block(along,y,cursor);
                        var shape=state.isSolidRender()?Shapes.block():
                            state.canOcclude()?state.getFaceOcclusionShape(edge.face()):Shapes.empty();
                        if(shape.isEmpty()) return state.getFluidState().isEmpty()?Face.AIR:Face.FLUID;
                        // Only the exposed side above the LOD surface needs a vanilla material.
                        int appearance=0;
                        if(y>=lodY && !colored) {
                            int material=TextureMaterial.block(state,faceIndex);
                            int color=material==0?state.getMapColor(edge.chunk().getLevel(),cursor).col:0xffffff;
                            appearance=((material+1)<<24)|color;
                        }
                        return appearance==0 && shape==Shapes.block()?Face.SOLID:new Face(shape,appearance);
                    });
            }
            it.remove();
        }
    }

    record Face(VoxelShape shape,int appearance,boolean fluid) {
        private static final Face AIR=new Face(Shapes.empty(),0);
        private static final Face FLUID=new Face(Shapes.empty(),0,true);
        private static final Face SOLID=new Face(Shapes.block(),0);
        Face(VoxelShape shape,int appearance) { this(shape,appearance,false); }
    }

    void addColumn(int x,int z,Direction direction,int lodY,int vanillaY,int minY,boolean water,
                   int lodAppearance,int debugColor,IntFunction<Face> vanillaBlock) {
        float sideShade=direction.getAxis()==Direction.Axis.X?.72f:.86f;
        int low=Math.max(minY,Math.min(lodY,vanillaY)-SURFACE_DEPTH);
        int high=Math.max(lodY,vanillaY);
        int runStart=low,runSide=0,runAppearance=0;
        for(int y=low;y<=high;y++) {
            int side=0,appearance=0;
            VoxelShape visible=Shapes.empty();
            if(y<high) {
                var vanilla=vanillaBlock.apply(y);
                if(y<lodY) {
                    if(!water && !vanilla.fluid()) {
                        side=-1;
                        appearance=lodAppearance;
                        visible=vanilla.shape().isEmpty()?Shapes.block():
                            vanilla.shape()==Shapes.block()?Shapes.empty():
                            Shapes.joinUnoptimized(Shapes.block(),vanilla.shape(),BooleanOp.ONLY_FIRST);
                    }
                } else {
                    side=1;
                    appearance=vanilla.appearance();
                    visible=vanilla.shape();
                }
            }
            if(debugColor>=0) appearance=debugColor;
            else appearance=shade(appearance,sideShade);
            boolean full=visible==Shapes.block();
            if(full && runSide==side && runAppearance==appearance) continue;
            if(runSide!=0) quad(x,z,direction,runSide>0,0,1,runStart,y,runAppearance);
            runSide=0;
            if(full) {
                runStart=y;runSide=side;runAppearance=appearance;
            } else if(!visible.isEmpty()) {
                boolean xFace=direction.getAxis()==Direction.Axis.X;
                for(var box:visible.toAabbs()) {
                    quad(x,z,direction,side>0,xFace?box.minZ:box.minX,xFace?box.maxZ:box.maxX,
                        y+box.minY,y+box.maxY,appearance);
                }
            }
        }
    }

    private void quad(int x,int z,Direction direction,boolean fromVanilla,double start,double end,
                      double low,double high,int appearance) {
        boolean xFace=direction.getAxis()==Direction.Axis.X;
        boolean forward=(direction.getStepX()-direction.getStepZ()>0)==fromVanilla;
        double a=forward?start:end,b=forward?end:start;
        double ax=x+(xFace?0:a),az=z+(xFace?a:0),bx=x+(xFace?0:b),bz=z+(xFace?b:0);
        point(ax,low,az,appearance);point(ax,high,az,appearance);point(bx,low,bz,appearance);
        point(bx,low,bz,appearance);point(ax,high,az,appearance);point(bx,high,bz,appearance);
    }

    private void point(double x,double y,double z,int appearance) {
        vertex.clear();
        vertex.putFloat((float)(x-originX)).putFloat((float)y).putFloat((float)(z-originZ))
            .put((byte)(appearance>>>16)).put((byte)(appearance>>>8)).put((byte)appearance).put((byte)(appearance>>>24));
        out.writeBytes(vertex.array());
    }

    private static int shade(int appearance,float factor) {
        int r=Math.round((appearance>>>16&255)*factor);
        int g=Math.round((appearance>>>8&255)*factor);
        int b=Math.round((appearance&255)*factor);
        return (appearance&0xff000000)|(r<<16)|(g<<8)|b;
    }
}
