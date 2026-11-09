package dev.faraway.k7q4m9.mesh;

import dev.faraway.k7q4m9.terrain.*;
import java.nio.*;
import java.util.*;

/** Pure vegetation checks and microbench: no game, worldgen, server or registry bootstrap. */
public final class VegetationCheck {
    private static final VegetationField.Profile FOREST=new VegetationField.Profile(.9,13,6,0x456b32,false);
    private static final VegetationField.Profile EMPTY=new VegetationField.Profile(0,0,0,0,false);
    private static final VegetationField.Source SOURCE=(x,z) -> new VegetationField.Surface(64,false,FOREST);
    public static void main(String[] args) {
        for(int lod=1;lod<=5;lod++) {
            int spacing=1<<lod; var key=new TileKey(-1,0,spacing); var builder=new ApproxTreeMeshBuilder();
            var first=builder.build(key,123,SOURCE,0,0,100000,() -> false);
            var again=builder.build(key,123,SOURCE,0,0,100000,() -> false);
            check(Arrays.equals(bytes(first),bytes(again)),"Repeated seed/coordinates must produce identical mesh bytes");
            var other=builder.build(key,124,SOURCE,0,0,100000,() -> false);
            check(!Arrays.equals(bytes(first),bytes(other)),"Different seeds must change the forest");
            var neighbor=builder.build(new TileKey(0,0,spacing),123,SOURCE,0,0,100000,() -> false);
            var sharedA=boundary(first,true); var sharedB=boundary(neighbor,false);
            sharedA.retainAll(sharedB);
            check(sharedA.isEmpty(),"Neighbor tiles must not create opposing internal faces at their shared boundary");
            builder.build(new TileKey(2,0,spacing),123,SOURCE,0,0,100000,() -> false);
            check(Arrays.equals(bytes(first),bytes(builder.build(key,123,SOURCE,0,0,100000,() -> false))),"Generation order must not affect borders or tree placement");
            bounds(first);
            if(lod>=4) {
                check(builder.stats().candidates()==0,"Canopy LODs must not generate individual tree candidates");
                check(builder.stats().surfaceSamples()<=34*34,"Canopy sampling must remain constant per tile");
            }
            var empty=builder.build(key,123,(x,z) -> new VegetationField.Surface(64,false,EMPTY),0,0,100000,() -> false);
            check(empty.bytes()==0,"Treeless biomes must not generate vegetation");
            var wet=builder.build(key,123,(x,z) -> new VegetationField.Surface(64,true,FOREST),0,0,100000,() -> false);
            check(wet.bytes()==0,"Water surfaces must not spawn trees");
            // Tree crowns may extend over a biome boundary, but cannot create distant forest.
            var transition=builder.build(new TileKey(0,0,spacing),123,(x,z) -> new VegetationField.Surface(64,false,x<0?FOREST:EMPTY),0,0,100000,() -> false);
            var tv=transition.vertices().order(ByteOrder.nativeOrder());
            while(tv.hasRemaining()) { check(tv.getFloat()<=24,"Crowns at biome transitions must remain locally bounded"); tv.position(tv.position()+12); }
            long total=0;
            for(int i=0;i<12;i++) {
                builder.build(key,123,SOURCE,0,0,100000,() -> false);
                if(i>=2) total+=builder.stats().generationNs()+builder.stats().meshNs();
            }
            System.out.printf(java.util.Locale.ROOT,"LOD %d synthetic vegetation mean=%.3fms samples=%d candidates=%d voxels=%d triangles=%d%n",lod,total/10_000_000.0,
                builder.stats().surfaceSamples(),builder.stats().candidates(),builder.stats().voxels(),builder.stats().triangles());
        }
        // One canonical crown crossing a negative-coordinate LOD 1 / LOD 2 join.
        long crossingSeed=0;
        while(((VegetationField.hash(crossingSeed,-1,1)>>>8)&7)<4) crossingSeed++;
        var crossingProfile=new VegetationField.Profile(10,13,6,0x456b32,false);
        VegetationField.Source crossing=(x,z) -> new VegetationField.Surface(64,false,x==-8 && z==8?crossingProfile:EMPTY);
        var left=new ApproxTreeMeshBuilder().build(new TileKey(-1,0,2),crossingSeed,crossing,0,0,100000,() -> false);
        var right=new ApproxTreeMeshBuilder().build(new TileKey(0,0,4),crossingSeed,crossing,0,0,100000,() -> false);
        check(left.bytes()>0 && right.bytes()>0,"A crossing crown must exist on both sides of a mixed-LOD boundary");
        check(Math.abs(left.maxY()-right.maxY())<=4,"Mixed-LOD crown height must remain within one coarse vertical cell");
        bounds(left); bounds(right);
        boolean stopped=false;
        try { new ApproxTreeMeshBuilder().build(new TileKey(0,0,2),123,SOURCE,0,0,100000,() -> true); }
        catch(java.util.concurrent.CancellationException expected) { stopped=true; }
        check(stopped,"Cancelled generation must stop before publishing vegetation");
        System.out.println("Vegetation determinism, borders, biome transitions and LOD checks passed");
    }
    private static byte[] bytes(TileMesh m) { var b=m.vertices();var a=new byte[b.remaining()];b.get(a);return a; }
    private static void bounds(TileMesh mesh) {
        var b=mesh.vertices().order(ByteOrder.nativeOrder());
        for(int i=0;i<b.remaining();i+=16) check(b.getFloat(i)>=0 && b.getFloat(i)<=mesh.key().blocks() && b.getFloat(i+8)>=0 && b.getFloat(i+8)<=mesh.key().blocks(),"A tile must own only its part of a crossing crown");
    }
    private static Set<String> boundary(TileMesh mesh,boolean east) {
        var result=new HashSet<String>();var b=mesh.vertices().order(ByteOrder.nativeOrder());float edge=east?mesh.key().blocks():0;
        for(int i=0;i<b.remaining();i+=48) if(b.getFloat(i)==edge && b.getFloat(i+16)==edge && b.getFloat(i+32)==edge) {
            var points=new ArrayList<String>();
            for(int j=0;j<3;j++) points.add(b.getFloat(i+j*16+4)+":"+(mesh.key().originZ()+b.getFloat(i+j*16+8)));
            Collections.sort(points);result.add(points.toString());
        }
        return result;
    }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
