package dev.faraway.k7q4m9.diagnostics;

/** Focused acceptance checks; no game, GPU or timing-sensitive sleeps. */
public final class FrameRecorderCheck {
    public static void main(String[] args) throws Exception {
        checkMemorySettingsMigration();
        var rollingTiming=new RollingTimingWindow();
        rollingTiming.add(0,100);rollingTiming.add(500_000_000L,300);
        require(rollingTiming.average(999_999_999L)==200,"One-second timing mean must include each sample exactly once");
        require(rollingTiming.average(1_000_000_000L)==300,"Timing mean must drop samples one second old");
        require(Double.isNaN(rollingTiming.average(1_500_000_000L)),"Empty timing windows must report unavailable rather than a stale average");
        rollingTiming.clear();for(int i=0;i<600;i++)rollingTiming.add(i,7);
        require(rollingTiming.average(600)==7,"High-FPS timing windows must grow while preserving samples");
        var tenSecondTiming=new RollingTimingWindow(10_000_000_000L);tenSecondTiming.add(0,100);tenSecondTiming.add(5_000_000_000L,300);
        require(tenSecondTiming.average(9_999_999_999L)==200 && tenSecondTiming.average(10_000_000_000L)==300,"Ten-second mean must use its own sliding boundary");
        var tenSecondCalls=new DrawCallWindow(10_000_000_000L);tenSecondCalls.add(0,10);tenSecondCalls.add(5_000_000_000L,20);
        require(tenSecondCalls.sum(9_999_999_999L)==30 && tenSecondCalls.sum(10_000_000_000L)==20,"Ten-second call total must expire without scaling or bucket resets");
        var callWindow=new DrawCallWindow();
        callWindow.add(0,10);callWindow.add(500_000_000L,20);
        require(callWindow.sum(999_999_999L)==30,"Draw call/s must total every frame in the trailing second");
        require(callWindow.sum(1_000_000_000L)==20,"Calls exactly one second old must leave the window");
        callWindow.add(1_100_000_000L,5);
        require(callWindow.sum(1_500_000_000L)==5,"Window must slide continuously rather than resetting in one-second buckets");
        callWindow.clear();for(int i=0;i<600;i++)callWindow.add(i,1);
        require(callWindow.sum(600)==600,"Sliding counter must grow without losing high-FPS frame counts");
        require(callWindow.sum(1_000_001_000L)==0,"Expired frames must not remain in the total");
        // Six axis/sign combinations, fractional shoreline heights and distinct corner colors.
        for(int u=0;u<3;u++)for(int v=0;v<3;v++)if(u!=v) {
            var quad=java.nio.ByteBuffer.allocate(96).order(java.nio.ByteOrder.nativeOrder());
            float[] origin={32,62.889f,16};float du=-4,dv=8;
            for(int corner:new int[]{0,1,2,2,1,3}) {
                var point=origin.clone();if((corner&1)!=0)point[u]+=du;if((corner&2)!=0)point[v]+=dv;
                quad.putFloat(point[0]).putFloat(point[1]).putFloat(point[2]).putInt(0x07000000|corner*0x123456);
            }
            var packed=dev.faraway.k7q4m9.mesh.PackedQuads.pack(quad.array(),()->false);
            require(packed.quads().length==40 && packed.fallback().length==0,"Axis-aligned quads must pack without changing corner colors or clipping heights");
            var record=java.nio.ByteBuffer.wrap(packed.quads()).order(java.nio.ByteOrder.nativeOrder());
            require(record.getInt(20)==(u|(v<<2)) && record.getFloat(12)==du && record.getFloat(16)==dv,"Signed quad extents and axes must be lossless");
            for(int corner=0;corner<4;corner++)require(record.getInt(24+corner*4)==(0x07000000|corner*0x123456),"All four colors must survive packing");
            quad.putFloat(80,quad.getFloat(80)+.25f);
            var fallback=dev.faraway.k7q4m9.mesh.PackedQuads.pack(quad.array(),()->false);
            require(fallback.quads().length==0 && java.util.Arrays.equals(fallback.fallback(),quad.array()),"Unsupported triangles must retain their exact original bytes");
        }
        var faceVertices=java.nio.ByteBuffer.allocate(3*16).order(java.nio.ByteOrder.nativeOrder());
        for(float[] point:new float[][]{{-100,7,-100},{-100,7,-99},{-99,7,-100}})faceVertices.putFloat(point[0]).putFloat(point[1]).putFloat(point[2]).putInt(-1);
        faceVertices.flip();
        var faceRuns=dev.faraway.k7q4m9.render.FaceSelection.pack(faceVertices,new int[]{0,1,2,0,2,1});
        require(faceRuns.indices().length==6 && faceRuns.runs().size()==2,"Face grouping must preserve both triangle windings and shared vertices");
        require(faceRuns.runs().stream().filter(r->r.visible(-100,8,-100)).mapToInt(r->r.count()).sum()==3,"Camera above a face must select only its upward winding");
        require(faceRuns.runs().stream().filter(r->r.visible(-100,6,-100)).mapToInt(r->r.count()).sum()==3,"Camera below a face must select only its downward winding");
        var anchor=new net.minecraft.world.phys.Vec3(-100,8,-100);
        require(faceRuns.runs().stream().filter(r->r.visible(anchor.x,anchor.y,anchor.z)).findFirst().orElseThrow().y()>0,"Face selection must use the supplied camera anchor");

        var siblings=java.util.Set.of(new dev.faraway.k7q4m9.terrain.TileKey(-2,-2,2),new dev.faraway.k7q4m9.terrain.TileKey(-1,-2,2),new dev.faraway.k7q4m9.terrain.TileKey(-2,-1,2),new dev.faraway.k7q4m9.terrain.TileKey(-1,-1,2));
        var merged=dev.faraway.k7q4m9.streaming.DirectLodPlanner.coalesceSameLod(siblings,64);
        require(merged.equals(java.util.Set.of(new dev.faraway.k7q4m9.terrain.TileKey(-1,-1,2,4))),"Sibling coalescing must preserve LOD and cover the same negative-coordinate region");
        require(dev.faraway.k7q4m9.streaming.DirectLodPlanner.coalesceSameLod(siblings,32).equals(siblings),"Coalescing must respect the per-tile sample limit");
        var incomplete=new java.util.HashSet<>(siblings);incomplete.remove(new dev.faraway.k7q4m9.terrain.TileKey(-1,-1,2));
        require(dev.faraway.k7q4m9.streaming.DirectLodPlanner.coalesceSameLod(incomplete,64).equals(incomplete),"Incomplete quads must not introduce uncovered terrain");
        var disabledScatter=new dev.faraway.k7q4m9.streaming.LodScatter.Config(100,4,0,-50,50,1,false);
        require(dev.faraway.k7q4m9.streaming.LodScatter.selectLod(disabledScatter,new dev.faraway.k7q4m9.terrain.TileKey(0,0,1),8)==3 && dev.faraway.k7q4m9.streaming.LodScatter.multiplier(disabledScatter,new dev.faraway.k7q4m9.terrain.TileKey(0,0,1))==1,"Disabled noise must select base LOD without scatter");
        require(dev.faraway.k7q4m9.worldgen.SurfaceTerrainBuilder.bottom(70,-64)==62 && dev.faraway.k7q4m9.worldgen.SurfaceTerrainBuilder.bottom(-60,-64)==-64,"Temporary terrain must remain within its shallow surface band and world minimum");
        var mutableModel=new org.joml.Matrix4f().translation(10,0,0);
        var mutableTexture=new org.joml.Matrix4f();
        var mutableOffset=new org.joml.Vector3f(1,0,0);
        var mutableColor=new org.joml.Vector4f(1,1,1,1);
        var firstUniform=dev.faraway.k7q4m9.render.UniformTransforms.snapshot(mutableModel,mutableColor,mutableOffset,mutableTexture);
        mutableModel.translation(20,0,0); mutableOffset.set(2,0,0); mutableTexture.translation(3,0,0); mutableColor.set(0,0,0,1);
        var secondUniform=dev.faraway.k7q4m9.render.UniformTransforms.snapshot(mutableModel,mutableColor,mutableOffset,mutableTexture);
        require(firstUniform.modelView().m30()==10 && firstUniform.modelOffset().x()==1 && firstUniform.textureMatrix().m30()==0 && firstUniform.colorModulator().x()==1 && !firstUniform.equals(secondUniform),"Uniform cache snapshots must preserve each region despite reuse of mutable inputs");
        require(RenderProfile.INSTANCE!=null,"Render profiler must initialize without a game or GPU context");
        RenderProfile.INSTANCE.reset();
        var window=new TimingWindow(3);
        for(long value:new long[]{1,2,3,100}) window.add(value);
        var windowSummary=window.summary();
        require(windowSummary.count()==4 && windowSummary.retained()==3 && windowSummary.meanNs()==26.5 && windowSummary.maxNs()==100 && windowSummary.p50Ns()==3 && windowSummary.p95Ns()==100 && windowSummary.p99Ns()==100,"Timing windows must retain bounded percentile samples while preserving whole-window mean/max");
        window.clear(); require(window.summary().count()==0 && window.summary().p99Ns()==0,"Cleared timing windows must not retain previous-session samples");
        require(dev.faraway.k7q4m9.streaming.DirectLodPlanner.hysteresisLevel(1.6,1,20,30)==1,"Upper tolerance must retain two-block spacing at 2.5px for a 2px target");
        require(dev.faraway.k7q4m9.streaming.DirectLodPlanner.hysteresisLevel(1.5,1,20,30)==0,"Upper tolerance must refine when two-block spacing exceeds 2.6px");
        require(dev.faraway.k7q4m9.streaming.DirectLodPlanner.hysteresisLevel(2.4,0,20,30)==0,"Lower tolerance must reject a coarser candidate above 1.6px");
        require(dev.faraway.k7q4m9.streaming.DirectLodPlanner.hysteresisLevel(2.6,0,20,30)==1,"Lower tolerance must permit a coarser candidate below 1.6px");
        var planRoots=java.util.Set.of(new dev.faraway.k7q4m9.terrain.TileKey(0,0,1));
        var planRequest=new dev.faraway.k7q4m9.streaming.LodPlanningSession.Request(planRoots,0,100,0,-64,320,1080,70,2,128,4096,new dev.faraway.k7q4m9.streaming.LodScatter.Config(0,4,0),null,20,30);
        try(var syncPlanning=new dev.faraway.k7q4m9.streaming.LodPlanningSession();var backgroundPlanning=new dev.faraway.k7q4m9.streaming.LodPlanningSession()) {
            var expectedPlan=syncPlanning.update(planRequest,false,5,false);
            require(backgroundPlanning.update(planRequest,true,5,false)==null,"Async first request must defer acceptance until completion is polled");
            dev.faraway.k7q4m9.streaming.DirectLodPlanner.Plan asyncPlan=null;
            long deadline=System.nanoTime()+5_000_000_000L;
            while(asyncPlan==null && System.nanoTime()<deadline) { java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);asyncPlan=backgroundPlanning.update(planRequest,true,5,false); }
            require(expectedPlan.equals(asyncPlan),"Background and synchronous planning must publish the same immutable plan");
            require(backgroundPlanning.update(planRequest,true,5,true)==asyncPlan,"Benchmark lock must preserve the accepted plan identity");
        }
        var lodSeven=new dev.faraway.k7q4m9.terrain.TileKey(0,0,128);
        require(dev.faraway.k7q4m9.streaming.TileCoordinator.adaptiveVariants(java.util.Set.of(lodSeven),0,18,7).size()==2,"Configurable Adaptive Max LOD must allow LOD seven variants");
        var projectionMatrix=new org.joml.Matrix4f().perspective((float)Math.toRadians(70),1920f/1080,.05f,90_000_000);
        var screenLod=new dev.faraway.k7q4m9.streaming.ScreenSpaceLod(projectionMatrix,0,16,0,0,1920,1080);
        var centerTile=new dev.faraway.k7q4m9.terrain.TileKey(0,-4,1);
        var edgeTile=new dev.faraway.k7q4m9.terrain.TileKey(2,-4,1);
        require(screenLod.allowed(edgeTile,2,100)<screenLod.allowed(centerTile,2,100),"Screen-edge footprints must select finer samples than center footprints at equal depth");
        require(screenLod.allowed(centerTile,4,100)==2*screenLod.allowed(centerTile,2,100),"Sample Spacing must scale the projected allowance");
        require(screenLod.allowed(new dev.faraway.k7q4m9.terrain.TileKey(0,4,1),2,123)==123,"Behind-camera tiles must retain the radial fallback");
        require(screenLod.allowed(new dev.faraway.k7q4m9.terrain.TileKey(0,-1,1),2,123)==0,"Near-eye intersections must select full detail without invalid arithmetic");
        var adaptiveKey=new dev.faraway.k7q4m9.terrain.TileKey(0,0,8,1);
        var adaptive=dev.faraway.k7q4m9.streaming.TileCoordinator.adaptiveVariants(java.util.Set.of(adaptiveKey),0,18);
        require(adaptive.equals(java.util.Set.of(new dev.faraway.k7q4m9.terrain.TileKey(0,0,4,1),new dev.faraway.k7q4m9.terrain.TileKey(0,0,16,1))),"Adaptive variants must preserve footprint and prepare exactly adjacent LODs");
        require(dev.faraway.k7q4m9.streaming.TileCoordinator.adaptiveVariants(java.util.Set.of(new dev.faraway.k7q4m9.terrain.TileKey(0,0,1)),0,18).size()==1,"LOD zero must only prefetch one coarser variant");
        require(dev.faraway.k7q4m9.streaming.TileCoordinator.adaptiveVariants(java.util.Set.of(new dev.faraway.k7q4m9.terrain.TileKey(0,0,128)),0,18).isEmpty(),"LOD seven must not recursively prepare variants");
        var outlineTarget=new dev.faraway.k7q4m9.terrain.TileKey(0,0,1,2);
        var outlineSquares=java.util.List.of(new dev.faraway.k7q4m9.streaming.GenPreviewPlanner.Square(outlineTarget,0));
        var displayedChildren=new java.util.HashSet<dev.faraway.k7q4m9.terrain.TileKey>();
        for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++)displayedChildren.add(new dev.faraway.k7q4m9.terrain.TileKey(dx,dz,1,1));
        require(dev.faraway.k7q4m9.streaming.GenPreviewPlanner.pendingOnly(outlineSquares,displayedChildren).isEmpty(),"All displayed same-LOD children must hide their target outline");
        displayedChildren.remove(new dev.faraway.k7q4m9.terrain.TileKey(1,1,1,1));
        require(dev.faraway.k7q4m9.streaming.GenPreviewPlanner.pendingOnly(outlineSquares,displayedChildren).size()==1,"Partial target coverage must retain the outline");
        require(dev.faraway.k7q4m9.streaming.GenPreviewPlanner.pendingOnly(outlineSquares,java.util.Set.of(new dev.faraway.k7q4m9.terrain.TileKey(0,0,2,2))).size()==1,"A displayed different LOD must retain the target outline");
        require(dev.faraway.k7q4m9.streaming.GenPreviewPlanner.pendingOnly(outlineSquares,java.util.Set.of(new dev.faraway.k7q4m9.terrain.TileKey(0,0,1,4))).isEmpty(),"A larger displayed same-LOD tile must hide the outline");
        var distanceMotion=new dev.faraway.k7q4m9.client.FreecamMotion();
        require(distanceMotion.speed()==16 && distanceMotion.exponent()==0 && distanceMotion.multiplier()==1,"Freecam must start at exponent zero with a 16-block/s horizontal floor");
        require(distanceMotion.verticalSpeed()==32,"Vertical speed must apply 2^(x+2) to the eight-block base");
        distanceMotion.updateDistance(300,400);
        require(distanceMotion.speed()==125,"Horizontal distance must use 2^(x-2)");
        distanceMotion.scroll(1);
        require(Math.abs(distanceMotion.exponent()-0.05)<1e-9 && Math.abs(distanceMotion.speed()-500*Math.pow(2,0.05-2))<1e-9,"A wheel notch must add 0.05 to the exponent");
        distanceMotion.scroll(100);
        require(distanceMotion.exponent()==2 && distanceMotion.multiplier()==4,"Exponent must cap at 2");
        distanceMotion.scroll(-100);
        require(distanceMotion.exponent()==-2 && distanceMotion.multiplier()==0.25,"Exponent must floor at -2");
        distanceMotion.updateDistance(0,0);
        require(distanceMotion.speed()==16,"Horizontal speed floor must remain 16 blocks/s");
        distanceMotion.updateHeightDifference(-400);
        require(distanceMotion.verticalSpeed()==20 && distanceMotion.speed()==16,"Vertical speed must use the square root of absolute height difference independently");
        distanceMotion.updateHeightDifference(0);
        require(distanceMotion.verticalSpeed()==8,"Vertical base floor of eight must precede the multiplier");
        var verticalNear=new dev.faraway.k7q4m9.client.FreecamMotion();
        var verticalFar=new dev.faraway.k7q4m9.client.FreecamMotion();
        verticalNear.updateHeightDifference(10);verticalFar.updateHeightDifference(10);verticalFar.updateDistance(1000,1000);
        require(verticalNear.advance(0,0,1,0,0.1)[1]==verticalFar.advance(0,0,1,0,0.1)[1],"Horizontal distance must not affect actual vertical motion");
        var origin=new net.minecraft.world.phys.Vec3(0,100,0);
        var corners=java.util.List.of(new net.minecraft.world.phys.Vec3(-100,-100,100),new net.minecraft.world.phys.Vec3(100,-100,100),new net.minecraft.world.phys.Vec3(200,100,200),new net.minecraft.world.phys.Vec3(-200,100,200));
        var geometry=dev.faraway.k7q4m9.client.FreecamFrustum.geometry(new dev.faraway.k7q4m9.client.FreecamFrustum.Shape(origin,corners,java.util.List.of(),100,0,-30_000_000,30_000_000));
        var grid=geometry.wire();
        require(geometry.frameVertices()==32 && grid.size()>32,"Overlay frame and depth-tested strips must use disjoint draw ranges");
        var gridFirst=grid.get(36); var gridNext=grid.get(38); var gridNextTop=grid.get(39);
        require(Math.abs(gridFirst.x+100)<1e-8 && Math.abs(gridFirst.z-100)<1e-8,"Strip must start at the lower far-plane corner");
        require(Math.abs(gridFirst.distanceTo(gridNext)-(gridNextTop.y-gridNext.y))<1e-6,"Strip interval must match the taller line");
        for(int i=32;i<grid.size();i++) require(Math.abs(origin.x+grid.get(i).x)<=30_000_000.000001 && Math.abs(origin.z+grid.get(i).z)<=30_000_000.000001 && origin.y+grid.get(i).y<=1_000_000.000001,"Strip must remain within world and height limits");
        var downward=java.util.List.of(new net.minecraft.world.phys.Vec3(-50,-100,100),new net.minecraft.world.phys.Vec3(50,-100,100),new net.minecraft.world.phys.Vec3(400,-50,200),new net.minecraft.world.phys.Vec3(-100,-50,200));
        var seaGrid=dev.faraway.k7q4m9.client.FreecamFrustum.wire(new dev.faraway.k7q4m9.client.FreecamFrustum.Shape(origin,downward,java.util.List.of(),100,0,-500,500));
        require(seaGrid.subList(32,seaGrid.size()).stream().anyMatch(v->Math.abs(v.x+200)<1e-6 && Math.abs(v.z-400)<1e-6 && Math.abs(v.y+100)<1e-6),"First upper ray must determine its sea endpoint");
        require(seaGrid.subList(32,seaGrid.size()).stream().anyMatch(v->Math.abs(v.x-800)<1e-6 && Math.abs(v.z-400)<1e-6 && Math.abs(v.y+100)<1e-6),"Second sea endpoint must be used even beyond the world when the first is inside");
        boolean joined=false;
        for(int i=32;i+1<seaGrid.size();i+=2) {
            var a=seaGrid.get(i); var b=seaGrid.get(i+1);
            if(Math.abs(a.x+200)<1e-6 && Math.abs(b.x-800)<1e-6 && Math.abs(a.z-400)<1e-6 && Math.abs(b.z-400)<1e-6 && Math.abs(a.y+100)<1e-6 && Math.abs(b.y+100)<1e-6) joined=true;
        }
        require(joined,"Sea endpoints must be joined by a third strip");
        double leftLimit=Math.min(40,downward.get(3).subtract(downward.get(0)).length());
        double rightLimit=Math.min(40,downward.get(2).subtract(downward.get(1)).length());
        require(seaGrid.subList(32,seaGrid.size()).stream().anyMatch(v->Math.abs(v.x+200)<1e-6 && Math.abs(v.z-400)<1e-6 && Math.abs(v.y+100-leftLimit)<1e-6),"Left strip must use its far-plane side length as height limit");
        require(seaGrid.subList(32,seaGrid.size()).stream().anyMatch(v->Math.abs(v.x-800)<1e-6 && Math.abs(v.z-400)<1e-6 && Math.abs(v.y+100-rightLimit)<1e-6),"Right strip must use its far-plane side length as height limit");
        var frustum=dev.faraway.k7q4m9.client.FreecamFrustum.create(new net.minecraft.world.phys.Vec3(0,100,0),new net.minecraft.world.phys.Vec3(0,0,1),new net.minecraft.world.phys.Vec3(1,0,0),new net.minecraft.world.phys.Vec3(0,1,0),90,4,0,-150,150);
        require(Math.abs(frustum.distance()-100)<0.001,"A sea-level edge crossing the map must be accepted even when both corners are outside");
        var outside=dev.faraway.k7q4m9.client.FreecamFrustum.create(new net.minecraft.world.phys.Vec3(1000,100,1000),new net.minecraft.world.phys.Vec3(0,0,1),new net.minecraft.world.phys.Vec3(1,0,0),new net.minecraft.world.phys.Vec3(0,1,0),90,1,0,-150,150);
        require(outside.distance()==10_000_000,"An edge outside the world must use the fallback distance");
        var asymmetric=dev.faraway.k7q4m9.client.FreecamFrustum.create(new net.minecraft.world.phys.Vec3(0,100,0),new net.minecraft.world.phys.Vec3(0,0,1),new net.minecraft.world.phys.Vec3(1,0.00001,0),new net.minecraft.world.phys.Vec3(0,1,0),90,1,0,-1000,1000);
        require(Math.abs(asymmetric.origin().y+asymmetric.corners().get(0).y)<1e-8 && Math.abs(asymmetric.origin().y+asymmetric.corners().get(1).y)<1e-8,"Both corner rays must intersect sea level independently");
        require(asymmetric.distance()<1000,"A small difference between ray slopes must not trigger the fallback");
        require(dev.faraway.k7q4m9.terrain.BiomeBlendPolicy.enabled(1,0) && !dev.faraway.k7q4m9.terrain.BiomeBlendPolicy.enabled(2,0),"Blend limit zero must include only D0");
        require(!dev.faraway.k7q4m9.terrain.BiomeBlendPolicy.enabled(1,-1),"Disabled blending must exclude D0 too");
        require(dev.faraway.k7q4m9.terrain.BiomeBlendPolicy.enabled(32,5) && !dev.faraway.k7q4m9.terrain.BiomeBlendPolicy.enabled(64,5),"Blend limit five must include D5 and exclude D6");
        require(dev.faraway.k7q4m9.render.LodColors.forSpacing(1)==0x0000ff && dev.faraway.k7q4m9.render.LodColors.forSpacing(262144)==0x0000ff,"LOD colors must cover D0 through D18");
        require(dev.faraway.k7q4m9.mesh.TextureMaterial.logType("spruce_log")==12 && dev.faraway.k7q4m9.mesh.TextureMaterial.logType("snowy_taiga")==12,"Spruce blocks and taiga vegetation must use spruce logs");
        require(dev.faraway.k7q4m9.mesh.TextureMaterial.logType("birch_log")==13 && dev.faraway.k7q4m9.mesh.TextureMaterial.logType("savanna")==15 && dev.faraway.k7q4m9.mesh.TextureMaterial.logType("cherry_grove")==18,"Tree species must select their own log textures");
        var view=new org.joml.Matrix4f();
        require(dev.faraway.k7q4m9.render.TileVisibility.entirelyBehind(new net.minecraft.world.phys.AABB(-1,-1,5,1,1,10),0,0,0,view),"Tiles entirely behind the camera must be culled");
        require(!dev.faraway.k7q4m9.render.TileVisibility.entirelyBehind(new net.minecraft.world.phys.AABB(-1,-1,-10,1,1,-5),0,0,0,view),"Tiles in front of the camera must remain visible");
        require(!dev.faraway.k7q4m9.render.TileVisibility.entirelyBehind(new net.minecraft.world.phys.AABB(-1,-1,-1,1,1,10),0,0,0,view),"Tiles crossing the camera plane must remain visible");
        view.rotationY((float)Math.PI);
        require(dev.faraway.k7q4m9.render.TileVisibility.entirelyBehind(new net.minecraft.world.phys.AABB(-1,-1,-10,1,1,-5),0,0,0,view),"Camera rotation must reverse the culling direction");
        var clip=new org.joml.Matrix4f().perspective((float)Math.toRadians(70),16f/9,.05f,1000f);
        require(dev.faraway.k7q4m9.render.TileVisibility.intersectsScreen(new net.minecraft.world.phys.AABB(-1,-1,-10,1,1,-5),0,0,0,clip),"A forward tile must contribute to visible HUD statistics");
        require(!dev.faraway.k7q4m9.render.TileVisibility.intersectsScreen(new net.minecraft.world.phys.AABB(100,-1,-10,110,1,-5),0,0,0,clip),"Tiles outside the screen must not contribute to HUD statistics");
        var edgeClip=new org.joml.Matrix4f().perspective((float)Math.toRadians(90),1,.05f,90_000_000f);
        var leftEdge=new net.minecraft.world.phys.AABB(-11,-1,-10,-9,1,-9);
        var rightEdge=new net.minecraft.world.phys.AABB(9,-1,-10,11,1,-9);
        require(dev.faraway.k7q4m9.render.TileVisibility.touchesHorizontalBoundary(leftEdge,0,0,0,edgeClip),"Left boundary tiles need outlines");
        require(dev.faraway.k7q4m9.render.TileVisibility.touchesHorizontalBoundary(rightEdge,0,0,0,edgeClip),"Right boundary tiles need outlines");
        require(!dev.faraway.k7q4m9.render.TileVisibility.touchesHorizontalBoundary(new net.minecraft.world.phys.AABB(-1,-1,-10,1,1,-9),0,0,0,edgeClip),"Interior tiles must not be outlined");
        require(!dev.faraway.k7q4m9.render.TileVisibility.touchesHorizontalBoundary(new net.minecraft.world.phys.AABB(-1,9,-10,1,11,-9),0,0,0,edgeClip),"Top boundary alone must not produce side outlines");
        require(!dev.faraway.k7q4m9.render.TileVisibility.touchesHorizontalBoundary(new net.minecraft.world.phys.AABB(100,-1,-10,110,1,-9),0,0,0,edgeClip),"Fully outside tiles must not be outlined");
        var edgeOrigin=new net.minecraft.world.phys.Vec3(29_999_000,1000,-29_999_000);
        require(dev.faraway.k7q4m9.render.TileVisibility.touchesHorizontalBoundary(leftEdge.move(edgeOrigin),edgeOrigin.x,edgeOrigin.y,edgeOrigin.z,edgeClip),"Boundary tests must preserve precision near world limits");
        var edgeA=new dev.faraway.k7q4m9.terrain.TileKey(0,0,1,2);
        var edgeB=new dev.faraway.k7q4m9.terrain.TileKey(2,0,1,1);
        var edgeC=new dev.faraway.k7q4m9.terrain.TileKey(2,1,1,1);
        var footprint=new dev.faraway.k7q4m9.render.TileFootprintEdges(java.util.List.of(edgeA,edgeB,edgeC));
        require(!footprint.hasExposedEdge(edgeA,dev.faraway.k7q4m9.render.TileFootprintEdges.POS_X),"Interior A must not be outlined when smaller B/C neighbours cover its outward edge");
        require(footprint.hasExposedEdge(edgeB,dev.faraway.k7q4m9.render.TileFootprintEdges.POS_X)&&footprint.hasExposedEdge(edgeC,dev.faraway.k7q4m9.render.TileFootprintEdges.POS_X),"Only outer B/C tiles should be outlined");
        var partialFootprint=new dev.faraway.k7q4m9.render.TileFootprintEdges(java.util.List.of(edgeA,edgeB));
        require(partialFootprint.hasExposedEdge(edgeA,dev.faraway.k7q4m9.render.TileFootprintEdges.POS_X),"A partially uncovered tile is still on the actual boundary");
        var smallEdge=new dev.faraway.k7q4m9.terrain.TileKey(1,0,1,1);
        var bigNeighbour=new dev.faraway.k7q4m9.terrain.TileKey(1,0,1,2);
        require(!new dev.faraway.k7q4m9.render.TileFootprintEdges(java.util.List.of(smallEdge,bigNeighbour)).hasExposedEdge(smallEdge,dev.faraway.k7q4m9.render.TileFootprintEdges.POS_X),"A larger neighbour must cover a smaller tile edge");
        var lookingUp=new org.joml.Matrix4f().perspective((float)Math.toRadians(70),16f/9,.05f,1000f).rotateX((float)-Math.PI/2);
        require(!dev.faraway.k7q4m9.render.TileVisibility.intersectsScreen(new net.minecraft.world.phys.AABB(-5,-100,-10,5,-90,-5),0,0,0,lookingUp),"Terrain below a camera looking upward must be outside the screen");
        dev.faraway.k7q4m9.worldgen.StructureMaskCheck.run();
        var timings=new dev.faraway.k7q4m9.streaming.LodTimingStats();
        timings.record(new dev.faraway.k7q4m9.terrain.TileKey(0,0,2,1),150_000_000L);
        timings.record(new dev.faraway.k7q4m9.terrain.TileKey(0,0,2,2),400_000_000L);
        timings.record(2,256,200_000_000L);
        var timing=timings.snapshot()[1];
        require(timing.detail()==2 && timing.tileCount()==3 && timing.sectorCount()==6 && timing.averageMsPerSector()==150
            && Math.abs(timing.cumulativeSeconds()-.75)<.00001,"Each tile contributes one normalized sample to the average");
        timings.record(1,64,7_000_000L);
        require(timings.snapshot()[0].sectorCount()==.25 && timings.snapshot()[0].averageMsPerSector()==28
            && Math.abs(timings.snapshot()[1].cumulativeSeconds()-.757)<.00001,"Fractional sectors and raw cumulative time must be preserved");
        var nextFrame=new dev.faraway.k7q4m9.streaming.LodTimingStats(); nextFrame.record(2,256,3_000_000L);
        require(nextFrame.snapshot()[0].tileCount()==0 && nextFrame.snapshot()[1].tileCount()==1 && nextFrame.snapshot()[1].averageMsPerSector()==3
            && Math.abs(nextFrame.snapshot()[1].cumulativeSeconds()-.003)<.00001,"Only tiles supplied for the current frame may contribute");
        for(int spacing:new int[]{1,2,4,8,16,32})
            require(dev.faraway.k7q4m9.streaming.TileCoordinator.supportsStructures(new dev.faraway.k7q4m9.terrain.TileKey(0,0,spacing))==(spacing<=8),
                "Structures must stop at the LOD 3 / LOD 4 boundary");
        dev.faraway.k7q4m9.terrain.VoxelGeometryCheck.run();
        var direct=new dev.faraway.k7q4m9.streaming.DirectLodPlanner();
        var independentTile=new dev.faraway.k7q4m9.terrain.TileKey(-2,3,4,2);
        double scatter=dev.faraway.k7q4m9.streaming.LodScatter.multiplier(1234,independentTile);
        require(scatter>=0.925 && scatter<=1.075 && scatter==dev.faraway.k7q4m9.streaming.LodScatter.multiplier(1234,independentTile),"LOD scatter must be bounded and deterministic");
        require(scatter!=dev.faraway.k7q4m9.streaming.LodScatter.multiplier(4321,independentTile),"LOD scatter must depend on the world seed");
        var neutral=new dev.faraway.k7q4m9.streaming.LodScatter.Config(0,4,0);
        var negative=new dev.faraway.k7q4m9.streaming.LodScatter.Config(100,4,0);
        require(dev.faraway.k7q4m9.streaming.LodScatter.multiplier(neutral,independentTile)==1,"Zero strength must disable scatter");
        double negativeFactor=dev.faraway.k7q4m9.streaming.LodScatter.multiplier(negative,independentTile);
        require(negativeFactor>=0.5 && negativeFactor<=1.5,"Centered noise must use both sides of one");
        require(negativeFactor!=dev.faraway.k7q4m9.streaming.LodScatter.multiplier(new dev.faraway.k7q4m9.streaming.LodScatter.Config(100,4,1),independentTile),"Seed offset must change the fixed-seed pattern");
        require(negativeFactor!=dev.faraway.k7q4m9.streaming.LodScatter.multiplier(new dev.faraway.k7q4m9.streaming.LodScatter.Config(100,8,0),independentTile),"Scatter scale must change the sampled pattern");
        require(dev.faraway.k7q4m9.streaming.LodScatter.multiplier(new dev.faraway.k7q4m9.streaming.LodScatter.Config(100,4,0,-25,-25),independentTile)==0.75,"Equal custom noise bounds must produce a constant multiplier");
        var early=new dev.faraway.k7q4m9.streaming.LodScatter.Config(20,4,0,-50,-50,1);
        var late=new dev.faraway.k7q4m9.streaming.LodScatter.Config(20,4,0,50,50,1);
        require(dev.faraway.k7q4m9.streaming.LodScatter.selectLod(early,independentTile,3.3)==2,"Dither must allow a coarser level inside the boundary");
        require(dev.faraway.k7q4m9.streaming.LodScatter.selectLod(late,independentTile,4.7)==1,"Dither must retain a finer level outside the boundary");
        require(dev.faraway.k7q4m9.streaming.LodScatter.selectLod(new dev.faraway.k7q4m9.streaming.LodScatter.Config(0,4,0,-50,50,1),independentTile,3.3)==1,"Zero-strength dither must keep the sharp boundary");
        require(independentTile.blocks()==64 && independentTile.originX()==-128 && independentTile.spacing()==4,"Tile extent and LOD sampling must have independent identities");
        require(!independentTile.equals(new dev.faraway.k7q4m9.terrain.TileKey(-2,3,4,4)),"Different tile sizes must not collide in caches");
        var previewEdges=dev.faraway.k7q4m9.streaming.PreviewEdges.build(java.util.List.of(
            new dev.faraway.k7q4m9.streaming.GenPreviewPlanner.Square(new dev.faraway.k7q4m9.terrain.TileKey(0,0,2),1),
            new dev.faraway.k7q4m9.streaming.GenPreviewPlanner.Square(new dev.faraway.k7q4m9.terrain.TileKey(2,0,1),1),
            new dev.faraway.k7q4m9.streaming.GenPreviewPlanner.Square(new dev.faraway.k7q4m9.terrain.TileKey(2,1,1),2)),0,18);
        require(previewEdges.stream().anyMatch(e -> !e.outer() && e.x1()==64 && e.z1()==32 && e.z2()==64),"LOD transition must follow the different-LOD section of an unequal-size boundary");
        require(previewEdges.stream().noneMatch(e -> e.x1()==64 && e.x2()==64 && e.z1()==0 && e.z2()==32),"Equal-LOD neighbours must not draw an interior boundary");
        for(int center:new int[]{-77,0,53}) {
            var roots=new java.util.HashSet<dev.faraway.k7q4m9.terrain.TileKey>();
            for(int rz=-1;rz<=0;rz++) for(int rx=-1;rx<=0;rx++) roots.add(new dev.faraway.k7q4m9.terrain.TileKey(rx,rz,1024));
            var boundaryPlan=direct.plan(roots,center,80,center,-64,320,1080,70,4,10000,20000);
            var boundary=dev.faraway.k7q4m9.streaming.BenchmarkBoundarySelection.select(boundaryPlan.leaves(),center,80,center,-64,320,1080,70,4,20000);
            require(!boundary.isEmpty() && boundaryPlan.leaves().containsAll(boundary),"Boundary selection must only generate planned tiles");
            for(int lod=0;lod<=10;lod++) {
                int spacing=1<<lod;
                for(int quadrant=0;quadrant<4;quadrant++) {
                    boolean alongX=(quadrant&1)==0,positive=quadrant==0 || quadrant==3;
                    boolean crossPositive=quadrant==2 || quadrant==3;
                    var expected=boundaryPlan.leaves().stream().filter(k -> k.spacing()==spacing)
                        .filter(k -> (alongX?k.z():k.x())==Math.floorDiv(center-(crossPositive?0:1),k.blocks()))
                        .filter(k -> positive?(alongX?k.originX():k.originZ())>=center:(long)(alongX?k.originX():k.originZ())+k.blocks()<=center)
                        .min(java.util.Comparator.comparingLong(k -> positive?-(long)((alongX?k.originX():k.originZ())+k.blocks()):(long)(alongX?k.originX():k.originZ())));
                    if(expected.isPresent()) require(boundary.contains(expected.get()),"Boundary must select quadrant extremum at LOD "+lod+" center="+center+" quadrant="+quadrant);
                }
            }
        }
        var preview=new dev.faraway.k7q4m9.streaming.GenPreviewPlanner();
        var previewRoot=new dev.faraway.k7q4m9.terrain.TileKey(0,0,64);
        var previewSquares=preview.plan(java.util.Set.of(previewRoot),350,80,700,-64,320,1080,70,8,10000,100000);
        var levelsBySize=new java.util.HashMap<Integer,java.util.Set<Integer>>();
        for(var square:previewSquares) levelsBySize.computeIfAbsent(square.region().blocks(),ignored -> new java.util.HashSet<>()).add(square.lod());
        require(levelsBySize.values().stream().anyMatch(levels -> levels.size()>1),"Preview must allow different LOD colors for the same SOT");
        require(previewSquares.stream().allMatch(square -> square.lod()>=Integer.numberOfTrailingZeros(square.region().spacing())-(square.lod()>=4?1:0) && square.lod()<=Integer.numberOfTrailingZeros(square.region().spacing())+1),"Preview SOT must preserve the near layout and allow far tile coalescing");
        var actualPreviewPlan=direct.plan(java.util.Set.of(previewRoot),350,80,700,-64,320,1080,70,8,10000,100000);
        var expectedPreviewKeys=new java.util.HashSet<dev.faraway.k7q4m9.terrain.TileKey>();
        for(var square:previewSquares) expectedPreviewKeys.add(new dev.faraway.k7q4m9.terrain.TileKey(square.region().x(),square.region().z(),1<<square.lod(),square.region().sizeSpacing()));
        require(actualPreviewPlan.leaves().equals(expectedPreviewKeys),"Production tile size and LOD must match the approved preview");
        var seededPreview=new dev.faraway.k7q4m9.streaming.GenPreviewPlanner(1234).plan(java.util.Set.of(previewRoot),350,80,700,-64,320,1080,70,8,10000,100000);
        var seededDirect=new dev.faraway.k7q4m9.streaming.DirectLodPlanner(1234).plan(java.util.Set.of(previewRoot),350,80,700,-64,320,1080,70,8,10000,100000);
        var seededKeys=new java.util.HashSet<dev.faraway.k7q4m9.terrain.TileKey>();
        for(var square:seededPreview) seededKeys.add(new dev.faraway.k7q4m9.terrain.TileKey(square.region().x(),square.region().z(),1<<square.lod(),square.region().sizeSpacing()));
        require(seededDirect.leaves().equals(seededKeys),"Seeded preview must match the production plan");
        var configuredPreview=new dev.faraway.k7q4m9.streaming.GenPreviewPlanner(negative).plan(java.util.Set.of(previewRoot),350,80,700,-64,320,1080,70,8,10000,100000);
        var configuredDirect=new dev.faraway.k7q4m9.streaming.DirectLodPlanner(negative).plan(java.util.Set.of(previewRoot),350,80,700,-64,320,1080,70,8,10000,100000);
        var configuredKeys=new java.util.HashSet<dev.faraway.k7q4m9.terrain.TileKey>();
        for(var square:configuredPreview) configuredKeys.add(new dev.faraway.k7q4m9.terrain.TileKey(square.region().x(),square.region().z(),1<<square.lod(),square.region().sizeSpacing()));
        require(configuredDirect.leaves().equals(configuredKeys),"Configured scatter preview must match the production plan");
        var ditherPreview=new dev.faraway.k7q4m9.streaming.GenPreviewPlanner(early).plan(java.util.Set.of(previewRoot),350,80,700,-64,320,1080,70,8,10000,100000);
        var ditherDirect=new dev.faraway.k7q4m9.streaming.DirectLodPlanner(early).plan(java.util.Set.of(previewRoot),350,80,700,-64,320,1080,70,8,10000,100000);
        var ditherKeys=new java.util.HashSet<dev.faraway.k7q4m9.terrain.TileKey>();
        for(var square:ditherPreview) ditherKeys.add(new dev.faraway.k7q4m9.terrain.TileKey(square.region().x(),square.region().z(),1<<square.lod(),square.region().sizeSpacing()));
        require(ditherDirect.leaves().equals(ditherKeys),"Dither preview must match the production plan");
        var oldSameRegion=new dev.faraway.k7q4m9.terrain.TileKey(0,0,2,1);
        var newSameRegion=new dev.faraway.k7q4m9.terrain.TileKey(0,0,1,1);
        require(dev.faraway.k7q4m9.streaming.TileCoordinator.resolveTransitions(java.util.Set.of(oldSameRegion),java.util.Set.of(newSameRegion),java.util.Set.of(oldSameRegion),java.util.Set.of()).equals(java.util.Set.of(oldSameRegion)),"Same-size old LOD must stay until its replacement is ready");
        require(dev.faraway.k7q4m9.streaming.TileCoordinator.resolveTransitions(java.util.Set.of(oldSameRegion),java.util.Set.of(newSameRegion),java.util.Set.of(oldSameRegion,newSameRegion),java.util.Set.of()).equals(java.util.Set.of(newSameRegion)),"Same-size replacement must switch atomically");
        require(dev.faraway.k7q4m9.streaming.GenPreviewPlanner.missingOnly(previewSquares,java.util.List.of()).isEmpty(),"Generated areas must not appear as missing preview");
        var fallbackRoot=new dev.faraway.k7q4m9.terrain.TileKey(0,0,64);
        var levels=java.util.Set.of(new dev.faraway.k7q4m9.terrain.TileKey(0,0,1),new dev.faraway.k7q4m9.terrain.TileKey(0,0,2),new dev.faraway.k7q4m9.terrain.TileKey(0,0,4));
        require(dev.faraway.k7q4m9.streaming.TileCoordinator.visibleLevels(levels,1,1).equals(java.util.Set.of(new dev.faraway.k7q4m9.terrain.TileKey(0,0,2))),"Generation target must exclude levels outside both visibility limits");
        require(dev.faraway.k7q4m9.streaming.TileCoordinator.visibleLevels(levels,2,1).isEmpty(),"An inverted visibility range must exclude every tile");
        require(dev.faraway.k7q4m9.streaming.TileCoordinator.visibleLevels(levels,0,18).equals(levels),"Restoring visibility must restore all eligible targets");
        var unrelatedFallback=new dev.faraway.k7q4m9.terrain.TileKey(4,4,64);
        var children=java.util.Set.of(fallbackRoot.child(0,0),fallbackRoot.child(1,0),fallbackRoot.child(0,1),fallbackRoot.child(1,1));
        var partial=new java.util.HashSet<>(children); partial.remove(fallbackRoot.child(1,1)); partial.add(fallbackRoot); partial.add(unrelatedFallback);
        var previous=java.util.Set.of(fallbackRoot,unrelatedFallback);
        var targets=new java.util.HashSet<>(children); targets.add(unrelatedFallback);
        var held=dev.faraway.k7q4m9.streaming.TileCoordinator.resolveTransitions(previous,targets,partial,java.util.Set.of());
        require(held.equals(previous),"A coarse tile must remain visible until all replacement children are resident");
        var complete=new java.util.HashSet<>(targets); complete.add(fallbackRoot);
        require(dev.faraway.k7q4m9.streaming.TileCoordinator.resolveTransitions(previous,targets,complete,java.util.Set.of()).equals(targets),"All children must replace the coarse tile together once resident");
        require(dev.faraway.k7q4m9.streaming.TileCoordinator.resolveTransitions(children,java.util.Set.of(fallbackRoot),children,java.util.Set.of()).equals(children),"Fine tiles must remain visible while their coarse replacement is pending");
        require(dev.faraway.k7q4m9.streaming.TileCoordinator.resolveTransitions(children,java.util.Set.of(fallbackRoot),complete,java.util.Set.of()).equals(java.util.Set.of(fallbackRoot)),"A ready coarse tile must replace its children together");
        var root=new dev.faraway.k7q4m9.terrain.TileKey(0,0,64);
        var roots=java.util.Set.of(root);
        var near=direct.plan(roots,16,100,16,0,320,1080,70,8,256);
        require(!near.leaves().contains(root) && !near.leaves().isEmpty(),"Near terrain must be planned directly at finer target levels");
        var high=direct.plan(roots,16,1_000_000,16,0,320,1080,70,8,256);
        require(high.leaves().size()==1 && high.leaves().iterator().next().sizeSpacing()==root.sizeSpacing() && high.leaves().iterator().next().spacing()>root.spacing(),"Altitude must permit a coarser LOD without changing tile size");
        var smallRadius=direct.plan(roots,16,100,16,0,320,1080,70,8,10000,128);
        var largeRadius=direct.plan(roots,16,100,16,0,320,1080,70,8,10000,512);
        require(!smallRadius.leaves().isEmpty() && largeRadius.leaves().size()>smallRadius.leaves().size(),"Render distance must bound the generation plan");
        require(smallRadius.leaves().stream().allMatch(k -> dev.faraway.k7q4m9.streaming.DirectLodPlanner.intersects(k,16,16,128)),"No planned leaf may be entirely outside render distance");
        require(largeRadius.leaves().containsAll(smallRadius.leaves()),"Increasing distance must preserve the quality and keys of existing inner leaves");
        require(direct.plan(java.util.Set.of(new dev.faraway.k7q4m9.terrain.TileKey(-2,-2,64)),16,100,16,0,320,1080,70,8,10000,128).leaves().isEmpty(),"Roots outside render distance must not generate tiles");
        var limited=direct.plan(roots,16,100,16,0,320,1080,70,8,1);
        require(limited.budgetLimited() && limited.leaves().isEmpty() && limited.deferred().contains(root),"Budget-limited intermediate tiles must be deferred, not generated");
        var recorder = new FrameRecorder(3);
        recorder.start(100);
        recorder.frame(10_000_100);
        recorder.frame(20_000_101);
        var failed = recorder.stop().summary();
        require(failed.frames() == 2 && failed.over10ms() == 1, "Frame spikes must remain visible in diagnostics");
        require(failed.maxNs() == 10_000_001L && failed.p50Ns() == 10_000_000L, "Raw intervals must be retained");
        recorder.frame(30_000_101);
        require(recorder.stop().summary().frames() == 2, "Stopped recording must ignore frames");
        recorder.start(0);
        recorder.frame(10_000_000);
        require(recorder.stop().summary().over10ms() == 0, "Exactly ten milliseconds does not increment the diagnostic counter");
        recorder.start(0);
        for (int i = 1; i <= 4; i++) recorder.frame(i);
        var overflow = recorder.stop().summary();
        require(overflow.dropped() == 1, "Lost frames must be reported");
        recorder.start(0);
        require(recorder.stop().summary().frames() == 0, "Restart resets frame count");
        var fps = new GameFpsRecorder(3);
        fps.start(0);
        fps.observe(1, 0);
        require(!fps.stop().summary().passes(), "An empty or unprepared FPS capture cannot pass");
        fps.start(0);
        fps.observe(1_000_000_000L, 500);
        require(fps.stop().summary().passes(), "The game's 500 FPS must pass regardless of frame-time diagnostics");
        fps.start(0);
        fps.observe(1_000_000_000L, 100);
        fps.observe(1_100_000_000L, 99);
        require(!fps.stop().summary().passes(), "A displayed value below 100 must fail");
        fps.start(0);
        for (int i = 0; i < 4; i++) fps.observe(1_000_000_000L + i * 100_000_000L, 500);
        require(!fps.stop().summary().passes(), "Dropped FPS observations cannot prove acceptance");
        var budget = new dev.faraway.k7q4m9.streaming.ByteBudget(100);
        var lease = budget.tryReserve(80);
        require(lease != null && budget.tryReserve(21) == null, "Queued outputs cannot overcommit memory");
        lease.close(); lease.close();
        require(budget.used() == 0, "Cancellation and result cleanup cannot release one reservation twice");
        require(budget.peak()==80, "Releasing memory must preserve the observed high-water mark");
        var growing=budget.tryReserve(40);
        require(growing.tryGrow(90) && budget.used()==90 && !growing.tryGrow(101),"A combined terrain/structure payload may grow only within the byte budget");
        growing.close(); growing.close();
        require(budget.used()==0 && !growing.tryGrow(100),"Growing reservations must release their full size once and remain closed");
        var estimate=dev.faraway.k7q4m9.streaming.WorkingSetEstimate.plan(0,0,64,128,1,0);
        require(!estimate.minimumFits() && estimate.retentionTiles()>=estimate.prefetchedTiles() && estimate.prefetchedTiles()>=estimate.requiredTiles(),
            "Preflight must count both rings and reject a required working set that cannot fit even vertex buffers");
        var planner = new dev.faraway.k7q4m9.streaming.CoveragePlanner();
        var first = planner.update(-1,-1,64,128);
        require(first.contains(new dev.faraway.k7q4m9.terrain.TileKey(-1,-1)), "Negative coordinate coverage must contain its camera tile");
        require(first == planner.update(-2,-2,64,128), "Movement within a camera chunk reuses bounded coverage");
        boolean refused=false;
        try { planner.update(0,0,1_000_000,128); } catch(IllegalArgumentException expected) { refused=true; }
        require(refused, "A huge flat-grid request must be refused before allocating its tile set");
        var host=new ScenarioHost();
        var benchmark=new DistanceBenchmark(host);
        benchmark.start(64,0); advanceToResident(benchmark);
        benchmark.update(312_000_000_000L); benchmark.update(372_000_000_000L);
        benchmark.update(374_000_000_000L); benchmark.update(376_000_000_000L);
        require(host.result!=null && host.result.passed() && host.finished==7, "Acceptance requires matched resident/movement/teleport baseline and all four LOD captures");
        require(host.result.residentPassed(), "Resident acceptance must remain independently reportable from streaming");
        require(host.result.phases().size()==7 && host.rotatedSeconds==120, "Candidate evidence must retain each phase and execute the resident 360-degree route");
        host=new ScenarioHost(); benchmark=new DistanceBenchmark(host);
        benchmark.start(64,0); advanceToResident(benchmark);
        benchmark.update(312_000_000_000L); benchmark.update(372_000_000_000L);
        host.arrived=false; benchmark.update(374_000_000_000L); benchmark.update(376_000_000_000L);
        require(host.result==null, "Pre-teleport coverage must not pass while the destination has not arrived");
        host.arrived=true; benchmark.update(378_000_000_000L); benchmark.update(380_000_000_000L);
        require(host.result!=null && host.result.passed(), "Teleport requires destination arrival and fresh complete coverage");
        host=new ScenarioHost(); benchmark=new DistanceBenchmark(host);
        benchmark.start(64,0); advanceToResident(benchmark);
        host.covered=false; benchmark.update(193_000_000_000L);
        require(host.result!=null && !host.result.passed(), "High FPS cannot pass incomplete resident coverage");
        var searchHost=new SearchHost();
        var search=new DistanceSearch(searchHost,512);
        search.start();
        int iterations=0;
        while(search.active() && iterations++<30) {
            int radius=searchHost.next;
            boolean pass=radius<=160;
            search.accept(new DistanceBenchmark.Result(radius,pass,"fixture",1,java.util.List.of(),pass?null:DistanceBenchmark.Phase.RESIDENT,false,java.util.List.of()));
        }
        require(searchHost.report!=null && searchHost.report.highestStreamingPassedChunks()==160 && searchHost.report.failedUpperChunks()==192,
            "Search must narrow the passing/failing bracket to 32 chunks");
        var grouped=searchHost.report.attempts().stream().collect(java.util.stream.Collectors.groupingBy(DistanceSearch.Attempt::chunks,java.util.stream.Collectors.counting()));
        require(grouped.values().stream().allMatch(count -> count==3), "Every candidate, including the failing upper bound, requires three runs");
        searchHost=new SearchHost(); search=new DistanceSearch(searchHost,64); search.start();
        search.accept(new DistanceBenchmark.Result(64,false,"baseline",0,java.util.List.of(),DistanceBenchmark.Phase.BASELINE,false,java.util.List.of()));
        require(!search.active() && searchHost.report.failedUpperChunks()==null, "A failed baseline cannot establish a terrain distance bound");
        searchHost=new SearchHost(); search=new DistanceSearch(searchHost,64); search.start();
        for(int i=0;i<3;i++) search.accept(new DistanceBenchmark.Result(64,true,"fixture",1,java.util.List.of(),null,false,java.util.List.of()));
        require(searchHost.report.capped() && searchHost.report.failedUpperChunks()==null, "A search ceiling is not a proven maximum");
        searchHost=new SearchHost(); search=new DistanceSearch(searchHost,64); search.start();
        for(int i=0;i<3;i++) search.accept(new DistanceBenchmark.Result(64,false,"fixture",1,java.util.List.of(),DistanceBenchmark.Phase.COLD,false,java.util.List.of()));
        require(searchHost.next==32 && search.active(), "An initial terrain failure must try the 32-chunk fallback");
        for(int i=0;i<3;i++) search.accept(new DistanceBenchmark.Result(32,true,"fixture",1,java.util.List.of(),null,false,java.util.List.of()));
        require(searchHost.report.highestStreamingPassedChunks()==32 && searchHost.report.failedUpperChunks()==64, "Fallback establishes only the measured 32/64 bracket");
        System.out.println("FPS acceptance, streaming budgets and distance search checks passed");
    }

    private static void advanceToResident(DistanceBenchmark benchmark) {
        for(long seconds:new long[]{2,122,182,184,186,188,190,192}) benchmark.update(seconds*1_000_000_000L);
    }

    private static final class ScenarioHost implements DistanceBenchmark.Host {
        int finished;
        boolean covered=true,arrived=true;
        double rotatedSeconds;
        DistanceBenchmark.Result result;
        public void clearTerrain() {}
        public void startCandidate(int radius) {}
        public void startCapture(String label) {}
        public GameFpsRecorder.Summary finishCapture(String label) { finished++; return new GameFpsRecorder.Summary(100,0,500,600,0); }
        public dev.faraway.k7q4m9.render.PreviewRenderer.Metrics metrics() {
            return new dev.faraway.k7q4m9.render.PreviewRenderer.Metrics(64,20,covered?20:19,10,1_000_000,0,0,0,false,1000,1_000_000,0,true,10);
        }
        public void moveAlongRoute(double seconds) {}
        public void teleportRoute() {}
        public boolean teleportArrived() { return arrived; }
        public void rotateView(double seconds) { rotatedSeconds=seconds; }
        public void result(DistanceBenchmark.Result value) { result=value; }
    }

    private static final class SearchHost implements DistanceSearch.Host {
        int next;
        DistanceSearch.Report report;
        public void runCandidate(int radius) { next=radius; }
        public void finished(DistanceSearch.Report value) { report=value; }
    }

    private static void checkMemorySettingsMigration() throws Exception {
        var directory=java.nio.file.Files.createTempDirectory("faraway-settings-check-");
        var file=directory.resolve("faraway-lod.properties");
        var loader=net.fabricmc.loader.api.FabricLoader.getInstance();
        var config=loader.getClass().getDeclaredField("configDir");config.setAccessible(true);var previous=config.get(loader);config.set(loader,directory);
        dev.faraway.k7q4m9.client.LodSettings.Values original=null;
        try {
            original=dev.faraway.k7q4m9.client.LodSettings.get();
            var load=dev.faraway.k7q4m9.client.LodSettings.class.getDeclaredMethod("load");load.setAccessible(true);
            var properties=new java.util.Properties();properties.setProperty("vramGiB","-1");properties.setProperty("performanceMode","2");properties.setProperty("previewMode","1");
            var adaptive=readSettings(properties,file,load);
            require(adaptive.previewMode()==0 && adaptive.showPlannedLods() && adaptive.genPreview(),"Legacy Per Tile must migrate to the independent planned-LOD toggle");
            require(adaptive.vramGiB()==4 && adaptive.adaptiveLods() && adaptive.performance()==1,"Legacy Adaptive and Smart settings must migrate to a numeric limit, separate toggle and Balanced");
            properties.setProperty("vramGiB","0");var minimal=readSettings(properties,file,load);
            require(minimal.vramGiB()==2 && !minimal.adaptiveLods(),"Legacy Minimal must become a fixed 2 GiB limit without enabling Adaptive");
            properties.setProperty("vramGiB","1");properties.setProperty("adaptiveLods","true");properties.setProperty("performanceMode","1");properties.setProperty("previewMode","3");properties.setProperty("showPlannedLods","true");
            var independent=readSettings(properties,file,load);
            require(independent.previewMode()==3 && independent.showPlannedLods(),"Boundary outlines and planned tiles must be independently enabled together");
            properties.setProperty("showPlannedLods","false");
            var edgesOnly=readSettings(properties,file,load);
            require(edgesOnly.previewMode()==3 && !edgesOnly.showPlannedLods() && edgesOnly.genPreview(),"Disabling planned tiles must keep boundary outlines active");
            properties.setProperty("previewMode","0");
            require(!readSettings(properties,file,load).genPreview(),"Disabling both controls must stop preview work");
            properties.setProperty("showPlannedLods","true");
            var plannedOnly=readSettings(properties,file,load);
            require(plannedOnly.previewMode()==0 && plannedOnly.showPlannedLods() && plannedOnly.genPreview(),"Planned tiles must work with boundary outlines off");
            require(independent.vramGiB()==1 && independent.adaptiveLods() && independent.performance()==3,"Adaptive must work independently at a 1 GiB limit while preserving Max performance");
            int[] expectedWorkers={6,10,16,20};
            for(int effort=0;effort<4;effort++) {
                require(dev.faraway.k7q4m9.client.LodSettings.workersForEffort(20,effort)==expectedWorkers[effort],"Generation effort percentages must use 30/50/80/100 percent of the limit");
                require(dev.faraway.k7q4m9.client.LodSettings.workersForEffort(1,effort)==1,"Effort must always retain at least one worker");
                properties.setProperty("generationEffort",Integer.toString(effort));
                require(readSettings(properties,file,load).performance()==effort,"All four new effort values must survive persistence without legacy remapping");
            }
            properties.setProperty("samplePixels","32");require(readSettings(properties,file,load).samplePixels()==4,"Old sample spacing values must clamp to the new maximum");
            for(int spacing=1;spacing<=4;spacing++) {properties.setProperty("samplePixels",Integer.toString(spacing));require(readSettings(properties,file,load).samplePixels()==spacing,"All four sample spacing values must load exactly");}
            properties.setProperty("planeOnly","true");
            var oldPlane=readSettings(properties,file,load);
            require(oldPlane.generatePlaneTiles() && !oldPlane.generateVoxelTiles(),"Legacy Plane Only must migrate to independent generation toggles");
            for(boolean planes:new boolean[]{false,true})for(boolean voxels:new boolean[]{false,true}) {
                properties.setProperty("generatePlaneTiles",Boolean.toString(planes));properties.setProperty("generateVoxelTiles",Boolean.toString(voxels));
                properties.setProperty("generateStructures",Boolean.toString(planes));properties.setProperty("generateTrees",Boolean.toString(voxels));
                var flags=readSettings(properties,file,load);
                require(flags.generatePlaneTiles()==planes && flags.generateVoxelTiles()==voxels && flags.generationEnabled()==(planes||voxels),"Tile type controls must remain independent and stop work when both are disabled");
                dev.faraway.k7q4m9.client.LodSettings.save(flags);dev.faraway.k7q4m9.client.LodSettings.setRenderDistance(128);
                var restored=dev.faraway.k7q4m9.client.LodSettings.get();
                require(restored.generateStructures()==planes && restored.generateTrees()==voxels && restored.generatePlaneTiles()==planes && restored.generateVoxelTiles()==voxels,"Distance updates must preserve all four independent generation controls");
                var saved=new java.util.Properties();try(var input=java.nio.file.Files.newInputStream(file)){saved.load(input);}
                var reloaded=readSettings(saved,file,load);
                require(reloaded.generateStructures()==planes && reloaded.generateTrees()==voxels,"Structures and Trees must save and reload independently");
            }
            properties.setProperty("rotatedTextures","false");
            var unrotated=readSettings(properties,file,load);
            require(!unrotated.randomizedTextures(),"Randomized Textures must inherit the old Rotated Textures setting");
            dev.faraway.k7q4m9.client.LodSettings.save(unrotated);
            dev.faraway.k7q4m9.client.LodSettings.setRenderDistance(128);
            require(!dev.faraway.k7q4m9.client.LodSettings.get().randomizedTextures(),"Distance changes must preserve Randomized Textures");
            require(independent.anisotropicFiltering()==4,"Existing settings must default to 4x anisotropic filtering");
            for(int filtering:new int[]{1,2,4,8,16}) {
                properties.setProperty("anisotropicFiltering",Integer.toString(filtering));
                var filtered=readSettings(properties,file,load);
                require(filtered.anisotropicFiltering()==filtering,"Filtering options must load exactly, including Off as 1x");
                dev.faraway.k7q4m9.client.LodSettings.save(filtered);
                dev.faraway.k7q4m9.client.LodSettings.setRenderDistance(128);
                require(dev.faraway.k7q4m9.client.LodSettings.get().anisotropicFiltering()==filtering,"Changing distance must preserve the independent texture sampler setting");
                var saved=new java.util.Properties();try(var input=java.nio.file.Files.newInputStream(file)){saved.load(input);}
                require(readSettings(saved,file,load).anisotropicFiltering()==filtering,"Texture filtering must survive save/reload");
            }
            dev.faraway.k7q4m9.client.LodSettings.save(independent);
            dev.faraway.k7q4m9.client.LodSettings.setRenderDistance(128);
            var changed=dev.faraway.k7q4m9.client.LodSettings.get();
            require(changed.adaptiveLods() && changed.vramGiB()==1 && changed.showPlannedLods() && changed.previewMode()==3 && dev.faraway.k7q4m9.client.LodSettings.activeWorkers()==changed.workers(),"Distance changes must preserve Adaptive and VRAM; Max must use the worker limit");
        } finally {
            if(original!=null)dev.faraway.k7q4m9.client.LodSettings.save(original);
            config.set(loader,previous);java.nio.file.Files.deleteIfExists(file);java.nio.file.Files.deleteIfExists(directory);
        }
    }
    private static dev.faraway.k7q4m9.client.LodSettings.Values readSettings(java.util.Properties properties,java.nio.file.Path file,java.lang.reflect.Method load) throws Exception {
        try(var out=java.nio.file.Files.newOutputStream(file)){properties.store(out,"test");}
        return (dev.faraway.k7q4m9.client.LodSettings.Values)load.invoke(null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
