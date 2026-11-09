package dev.faraway.k7q4m9.terrain;

import java.nio.*;
import dev.faraway.k7q4m9.mesh.*;

/** Regression cases for shallow water, adjacent LOD edges, and the ocean-only fast path. */
public final class VoxelGeometryCheck {
    public static void run() {
        var planeKey=new TileKey(0,0,16384,8192);
        var planeSampler=new dev.faraway.k7q4m9.worldgen.SurfaceGridSampler() {
            public int minY() { return -64; } public int maxY() { return 320; } public int seaLevel() { return 63; }
            public dev.faraway.k7q4m9.worldgen.SurfaceSample[] sampleRegion(int x,int z,int width,int depth,int spacing,java.util.function.BooleanSupplier cancelled) {
                check(width==16 && depth==16 && spacing==16384,"Plane sampling must respect independent SOT and LOD");
                var result=new dev.faraway.k7q4m9.worldgen.SurfaceSample[width*depth];
                java.util.Arrays.fill(result,new dev.faraway.k7q4m9.worldgen.SurfaceSample((short)60,(short)63,0,0x112233,0x345678,true,true)); return result;
            }
        };
        var plane=FarPlaneTile.generate(planeKey,planeSampler,() -> false);
        check(plane.rgba().length==1024 && (plane.rgba()[0]&255)==0x34 && (plane.rgba()[1]&255)==0x56 && (plane.rgba()[2]&255)==0x78,"Plane must retain only the selected surface color grid");
        var planeMesh=TileMesh.farPlane(plane);
        check(planeMesh.triangleCount()==2 && planeMesh.bytes()==1024 && !planeMesh.vertices().hasRemaining() && planeMesh.optimize(true,() -> false)==planeMesh,"Plane payload must use shared geometry and retain its color payload through optimization");
        var forestProfile=new VegetationField.Profile(.85,10,5,0x456b32,false);
        var forestSurface=new dev.faraway.k7q4m9.worldgen.SurfaceSample((short)80,(short)0,0,0x987654,0x345678,true,false,0,0x66aa44);
        var forestSampler=new dev.faraway.k7q4m9.worldgen.SurfaceGridSampler() {
            public long seed(){return 12345;}
            public int minY(){return -64;}public int maxY(){return 320;}public int seaLevel(){return 63;}
            public VegetationField.Profile vegetationProfile(int id){return forestProfile;}
            public dev.faraway.k7q4m9.worldgen.SurfaceSample[] sampleRegion(int x,int z,int w,int d,int spacing,java.util.function.BooleanSupplier c){var a=new dev.faraway.k7q4m9.worldgen.SurfaceSample[w*d];java.util.Arrays.fill(a,forestSurface);return a;}
        };
        var forestKey=new TileKey(-1,0,2,2);
        var forestPlane=FarPlaneTile.generate(forestKey,forestSampler,()->false);
        var repeat=FarPlaneTile.fromSamples(forestKey,forestSampler,VoxelTile.sampleSurface(forestKey,forestSampler,()->false),()->false);
        check(java.util.Arrays.equals(forestPlane.rgba(),repeat.rgba()),"Fresh and cached plane generation must use identical deterministic forest colors");
        var noTrees=FarPlaneTile.generate(forestKey,forestSampler,false,()->false);
        var cachedNoTrees=FarPlaneTile.fromSamples(forestKey,forestSampler,VoxelTile.sampleSurface(forestKey,forestSampler,()->false),false,()->false);
        check(java.util.Arrays.equals(noTrees.rgba(),cachedNoTrees.rgba()),"Trees off must also apply when reusing surface samples");
        for(int i=0;i<noTrees.rgba().length;i+=4)check((noTrees.rgba()[i]&255)==0x98 && (noTrees.rgba()[i+1]&255)==0x76 && (noTrees.rgba()[i+2]&255)==0x54,"Trees off must retain ground colors without forest canopy");
        var cache=new dev.faraway.k7q4m9.worldgen.DecorationCache();
        for(boolean structures:new boolean[]{false,true})for(boolean trees:new boolean[]{false,true}) {
            var result=cache.get(forestKey,structures,trees,4096,()->new dev.faraway.k7q4m9.worldgen.DetachedDecorationSampler.Result(java.util.Map.of(),0,0));
            check(result.source()==dev.faraway.k7q4m9.worldgen.DecorationCache.Source.GENERATED,"Each Structures/Trees combination needs a separate cached result");
            check(cache.get(forestKey,structures,trees,4096,()->{throw new AssertionError("Cache miss");}).source()==dev.faraway.k7q4m9.worldgen.DecorationCache.Source.HIT,"Unchanged decoration settings must reuse their cached result");
        }
        var smaller=FarPlaneTile.generate(new TileKey(-2,0,2,1),forestSampler,()->false);
        int canopy=0;var colors=new java.util.HashSet<Integer>();
        for(int z=0;z<smaller.side();z++)for(int x=0;x<smaller.side();x++) {
            int a=(z*forestPlane.side()+x)*4,b=(z*smaller.side()+x)*4;
            for(int c=0;c<4;c++)check(forestPlane.rgba()[a+c]==smaller.rgba()[b+c],"Canopy must use absolute coordinates, independent of tile size and borders");
            int color=(smaller.rgba()[b]&255)<<16|(smaller.rgba()[b+1]&255)<<8|smaller.rgba()[b+2]&255;
            if(color!=forestSurface.color())canopy++;colors.add(color);
        }
        check(canopy>0 && canopy<smaller.side()*smaller.side() && colors.size()>2,"Forest texture must contain natural crown patches, ground gaps and color variation");
        for(int spacing:new int[]{1,2,4,8,16,32}) {
            var waterSurface=new dev.faraway.k7q4m9.worldgen.SurfaceSample((short)60,(short)63,0,0x987654,0x345678,true,true,0,0x66aa44);
            check(ForestCanopy.surfaceColor(12345,-7,11,spacing,waterSurface,forestProfile)==0x345678,"Water must not be painted with canopy");
            check(ForestCanopy.surfaceColor(12345,-7,11,spacing,forestSurface,dev.faraway.k7q4m9.worldgen.VegetationProfiles.EMPTY)==forestSurface.color(),"Treeless biomes must retain their surface colors at every canopy scale");
        }
        var nearest=new TileKey(0,0,1);
        check(dev.faraway.k7q4m9.streaming.TileCoordinator.shouldUsePlane(nearest,63,200,5,true,true) && dev.faraway.k7q4m9.streaming.TileCoordinator.shouldUsePlane(nearest,63,200,5,true,false),"Plane Only must include LOD 0 and override both voxel and smooth terrain");
        check(!dev.faraway.k7q4m9.streaming.TileCoordinator.shouldUsePlane(nearest,63,200,5,false,true) && !dev.faraway.k7q4m9.streaming.TileCoordinator.shouldUsePlane(nearest,63,200,5,false,false),"Disabling Plane Only must restore the original representation rule");
        check(!dev.faraway.k7q4m9.streaming.TileCoordinator.shouldUsePlane(planeKey,63,64,5,false,true,true),"Voxel-only generation must override a normal plane decision");
        check(dev.faraway.k7q4m9.streaming.TileCoordinator.shouldUsePlane(nearest,63,200,5,true,false,false),"Plane-only generation must override Smooth Terrain");
        var gridKey=new TileKey(-1,2,8);
        var gridSampler=new dev.faraway.k7q4m9.worldgen.SurfaceGridSampler() {
            public int minY() { return -64; }
            public int maxY() { return 320; }
            public int seaLevel() { return 63; }
            public dev.faraway.k7q4m9.worldgen.SurfaceSample[] sampleRegion(int x,int z,int width,int depth,int spacing,java.util.function.BooleanSupplier cancelled) {
                check(x==gridKey.originX()-8 && z==gridKey.originZ()-8 && width==34 && depth==34 && spacing==8,"Surface grid must include the globally aligned one-cell halo");
                var result=new dev.faraway.k7q4m9.worldgen.SurfaceSample[width*depth];
                for(int i=0;i<result.length;i++) result[i]=new dev.faraway.k7q4m9.worldgen.SurfaceSample((short)(64+i%width),(short)0,0,0x777777,0,true,false);
                return result;
            }
        };
        var sampled=VoxelTile.generate(gridKey,gridSampler,() -> false,true);
        check(sampled.terrainSurface(-1,-1)==64 && sampled.terrainSurface(32,32)==97,"Surface provider must preserve grid order and halo samples");
        var perimeter=new VoxelMeshBuilder().build(sampled,()->false).optimize(true,()->false).perimeter();
        check(perimeter.startY(MeshPerimeter.WEST,0)==64 && perimeter.endY(MeshPerimeter.WEST,31)==64,
            "Frustum outline must use the bottom of the exposed wall instead of the top of the voxel");
        check(perimeter.startY(MeshPerimeter.NORTH,0)==65 && perimeter.startY(MeshPerimeter.NORTH,31)==96,
            "Frustum outline must retain individual terrain steps when there is no lower wall edge");
        var decisionKey=new TileKey(0,0,64);
        int decisionSide=decisionKey.blocks()/decisionKey.spacing(),decisionStride=decisionSide+2;
        var decisionSamples=new dev.faraway.k7q4m9.worldgen.SurfaceSample[decisionStride*decisionStride];
        var level=new dev.faraway.k7q4m9.worldgen.SurfaceSample((short)64,(short)0,0,0x112233,0,true,false);
        java.util.Arrays.fill(decisionSamples,level);
        int savedMaximum=FarPlaneTile.maxSurfaceY(decisionKey,63,decisionSamples);
        check(savedMaximum==64 && FarPlaneTile.usePlaneForMaximum(decisionKey,63,savedMaximum,50)==FarPlaneTile.usePlane(decisionKey,63,decisionSamples,50),"Stored maximum must reproduce the original decision");
        check(dev.faraway.k7q4m9.streaming.TileCoordinator.representationNeedsReplacement(decisionKey,63,savedMaximum,false,50),"Lowering the threshold must replace a voxel when the decision changes");
        check(!dev.faraway.k7q4m9.streaming.TileCoordinator.representationNeedsReplacement(decisionKey,63,savedMaximum,true,50),"An unchanged plane must remain resident");
        check(dev.faraway.k7q4m9.streaming.TileCoordinator.representationNeedsReplacement(decisionKey,63,savedMaximum,true,100),"Raising the threshold must replace a plane when the decision changes");
        check(!dev.faraway.k7q4m9.streaming.TileCoordinator.representationNeedsReplacement(decisionKey,63,savedMaximum,false,100),"An unchanged voxel must remain resident");
        check(!FarPlaneTile.usePlane(decisionKey,63,decisionSamples,60),"Sea level offset must affect the plane threshold");
        check(!FarPlaneTile.usePlane(decisionKey,63,decisionSamples,100) && FarPlaneTile.usePlane(decisionKey,63,decisionSamples,50),"Plane selection must use the configured threshold");
        check(FarPlaneTile.usePlane(decisionKey,63,decisionSamples,57) && !FarPlaneTile.usePlane(decisionKey,63,decisionSamples,58),"Offset must place the threshold between 57 and 58");
        decisionSamples[decisionStride+1]=new dev.faraway.k7q4m9.worldgen.SurfaceSample((short)65,(short)0,0,0x112233,0,true,false);
        check(!FarPlaneTile.usePlane(decisionKey,63,decisionSamples,60),"A larger sampled elevation must select voxels");
        decisionSamples[decisionStride+1]=level;
        var reusedPlane=FarPlaneTile.fromSamples(decisionKey,63,decisionSamples,() -> false);
        check(reusedPlane.side()==decisionSide && (reusedPlane.rgba()[0]&255)==0x11,"Plane colors must use the sampled grid without its halo");
        var fine=sea(new TileKey(1,0,16),16,24,true);
        var coarse=sea(new TileKey(1,0,32),16,24,true);
        var ocean=sea(new TileKey(0,0,64),16,0,false);
        var builder=new VoxelMeshBuilder();
        var a=builder.build(fine,() -> false); var b=builder.build(coarse,() -> false);
        check(!a.vertices().hasRemaining(),"Opaque surface water must remove all submerged seabed geometry");
        check(topArea(a)==Math.pow(fine.key().blocks(),2) && topArea(b)==Math.pow(coarse.key().blocks(),2),"Shallow water must cover the whole tile at both LOD levels");
        check(fine.key().originX()+fine.key().blocks()==coarse.key().originX(),"Fixtures must share an edge");
        check(edgeHeight(a,true)==edgeHeight(b,false),"Adjacent LOD water edges must meet at the same world height");
        var waterOnly=builder.build(ocean,() -> false);
        check(!waterOnly.vertices().hasRemaining(),"Ocean-only tiles must not generate an underwater voxel mesh");
        check(waterOnly.waterVertexCount()==6,"Uniform sea should contain only one surface quad, with no side faces");
        var transition=sea(new TileKey(0,0,32),16,0,false);
        for(int z=-1;z<=32;z++) for(int x=-1;x<=32;x++) transition.surface(x,z,61,63,x<16?0x204080:0x6080c0,true);
        var blendedWater=builder.build(transition,() -> false);
        check(blendedWater.waterVertexCount()==6,"Biome color changes must not split a flat water quad");
        var waterColors=blendedWater.waterVertices();
        boolean different=false; int initial=waterColors.get(12)&255;
        for(int i=16;i<waterColors.remaining();i+=16) if((waterColors.get(i+12)&255)!=initial) different=true;
        check(different,"Merged water must retain colors at its corners");
        check(waterOnly.maxY()==63,"Water-only bounds must include the sea surface");
        for(int z=-1;z<=32;z++) for(int x=-1;x<=32;x++) fine.surface(x,z,x>=16?55:61,0,0,false);
        var cliff=builder.build(fine,() -> false).vertices().order(ByteOrder.nativeOrder());
        boolean side=false;
        for(int i=0;i<cliff.remaining();i+=48) if(cliff.getFloat(i)==256 && cliff.getFloat(i+16)==256 && cliff.getFloat(i+32)==256
            && Math.max(cliff.getFloat(i+4),Math.max(cliff.getFloat(i+20),cliff.getFloat(i+36)))>55) side=true;
        check(side,"Unequal surface heights within one vertical cell must retain their exposed side face");
        boolean cancelled=false;
        try { builder.build(fine,() -> true); } catch(java.util.concurrent.CancellationException expected) { cancelled=true; }
        check(cancelled,"Cancelled meshes must not publish partial geometry");
        var samples=new dev.faraway.k7q4m9.worldgen.SurfaceSample[LodTile.HALO*LodTile.HALO];
        java.util.Arrays.fill(samples,new dev.faraway.k7q4m9.worldgen.SurfaceSample((short)60,(short)63,0,0x777777,0x345678,true,true));
        var smooth=new TerrainMeshBuilder().build(new LodTile(new TileKey(0,0,16),samples),() -> false);
        check(!smooth.vertices().hasRemaining() && smooth.indexCount()==0,"Smooth ocean must not retain underwater terrain or boundary curtains");
        check(topArea(smooth)==Math.pow(smooth.key().blocks(),2),"Smooth ocean must retain its complete surface");
        var independentKey=new TileKey(0,0,32,16);
        var independentWater=sea(independentKey,16,0,false);
        check(independentWater.cells()==16 && topArea(builder.build(independentWater,() -> false))==Math.pow(independentKey.blocks(),2),"Voxel water must cover a tile with 16 samples per side");
        var independentSamples=new dev.faraway.k7q4m9.worldgen.SurfaceSample[19*19];
        java.util.Arrays.fill(independentSamples,new dev.faraway.k7q4m9.worldgen.SurfaceSample((short)60,(short)63,0,0x777777,0x345678,true,true));
        var independentSmooth=new TerrainMeshBuilder().build(new LodTile(independentKey,independentSamples),() -> false);
        check(topArea(independentSmooth)==Math.pow(independentKey.blocks(),2),"Smooth water must cover a tile with 16 samples per side");
        for(int i=0;i<samples.length;i++) if(i%LodTile.HALO-1>=16)
            samples[i]=new dev.faraway.k7q4m9.worldgen.SurfaceSample((short)68,(short)0,0,0x777777,0x345678,true,false);
        var coast=new TerrainMeshBuilder().build(new LodTile(new TileKey(0,0,16),samples),() -> false);
        var coastVertices=coast.vertices().order(ByteOrder.nativeOrder());
        var coastIndices=coast.indices().order(ByteOrder.nativeOrder());
        while(coastIndices.hasRemaining()) {
            int index=Short.toUnsignedInt(coastIndices.getShort())*16;
            if(coastVertices.getFloat(index)<256) check(coastVertices.getFloat(index+4)>=63,"Coastal triangles must be clipped at the opaque water surface");
        }
    }
    private static VoxelTile sea(TileKey key,int step,int layers,boolean solid) {
        var t=new VoxelTile(key,-64,step,layers);
        for(int z=-1;z<=t.cells();z++) for(int x=-1;x<=t.cells();x++) {
            t.surface(x,z,61,63,0x345678,true);
            if(solid) for(int y=0;y<8;y++) t.cell(x,y,z,1,0x777777);
        }
        return t;
    }
    private static double topArea(TileMesh mesh) {
        var b=mesh.waterVertices().order(ByteOrder.nativeOrder()); double area=0;
        for(int i=0;i<b.remaining();i+=48) {
            float ay=b.getFloat(i+4),by=b.getFloat(i+20),cy=b.getFloat(i+36);
            if(ay!=63 || by!=63 || cy!=63) continue;
            double ax=b.getFloat(i),az=b.getFloat(i+8),bx=b.getFloat(i+16),bz=b.getFloat(i+24),cx=b.getFloat(i+32),cz=b.getFloat(i+40);
            double up=(bz-az)*(cx-ax)-(bx-ax)*(cz-az);
            check(up>0,"Water triangles must face upward for hardware back-face culling");
            area+=up/2;
        }
        return area;
    }
    private static float edgeHeight(TileMesh mesh,boolean east) {
        var b=mesh.waterVertices().order(ByteOrder.nativeOrder()); float high=-Float.MAX_VALUE;
        for(int i=0;i<b.remaining();i+=16) if(b.getFloat(i)==(east?mesh.key().blocks():0)) high=Math.max(high,b.getFloat(i+4));
        return high;
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
