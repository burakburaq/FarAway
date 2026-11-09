package dev.faraway.k7q4m9.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.renderpearl.api.pipeline.*;
import dev.faraway.k7q4m9.mesh.TileMesh;
import dev.faraway.k7q4m9.terrain.TileKey;
import dev.faraway.k7q4m9.worldgen.WorldDefinition;
import dev.faraway.k7q4m9.streaming.TileCoordinator;
import dev.faraway.k7q4m9.streaming.LodTimingStats;
import dev.faraway.k7q4m9.streaming.ByteBudget;
import dev.faraway.k7q4m9.client.LodSettings;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import dev.faraway.k7q4m9.diagnostics.RenderProfile;
import dev.faraway.k7q4m9.diagnostics.GpuPassProfile;
import static dev.faraway.k7q4m9.diagnostics.RenderProfile.Stage.*;

/** Fixed-spacing terrain preview and bounded M1 streaming renderer. */
public final class PreviewRenderer {
    private static final RenderPipeline PIPELINE = pipeline(false,false,false);
    private static final RenderPipeline VANILLA_SEAM = pipeline(false,false,false,false,true,true);
    private static final RenderPipeline VANILLA_SEAM_CULL = pipeline(false,false,false,true,true,true);
    private static final RenderPipeline WIREFRAME = pipeline(true,false,false);
    private static final RenderPipeline GRID_PIPELINE = pipeline(false,false,true);
    private static final RenderPipeline GRID_WIREFRAME = pipeline(true,false,true);
    private static final RenderPipeline WATER = pipeline(false,true,false);
    private static final RenderPipeline TERRAIN_NO_CULL=pipeline(false,false,false,false),WIREFRAME_NO_CULL=pipeline(true,false,false,false),WATER_NO_CULL=pipeline(false,true,false,false);
    private static final RenderPipeline PLANE=planePipeline(false),PLANE_WIREFRAME=planePipeline(true);
    private static final RenderPipeline PLANE_INSTANCED=planeInstancedPipeline(false),PLANE_INSTANCED_WIREFRAME=planeInstancedPipeline(true);
    private static final RenderPipeline PAINT_TERRAIN=pipeline(false,false,false,true,false),PAINT_TERRAIN_NO_CULL=pipeline(false,false,false,false,false);
    private static final RenderPipeline PAINT_WATER=pipeline(false,true,false,true,false),PAINT_WATER_NO_CULL=pipeline(false,true,false,false,false);
    private static final RenderPipeline PAINT_PLANE=planeInstancedPipeline(false,false);
    private static RenderPipeline planeInstancedPipeline(boolean wire) {return planeInstancedPipeline(wire,true);}
    private static RenderPipeline planeInstancedPipeline(boolean wire,boolean depthWrite) {
        return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.parse((wire?"faraway:pipeline/plane_instanced_wire":"faraway:pipeline/plane_instanced")+(depthWrite?"":"_paint")))
            .withVertexShader(Identifier.parse("faraway:core/plane_instanced")).withFragmentShader(Identifier.parse("faraway:core/plane_instanced"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("FarAwayCoverage",UniformType.UNIFORM_BUFFER).withUniform("FarAwayPlaneInstances",UniformType.UNIFORM_BUFFER).build())
            .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0_SAMPLER2)
            .withPolygonMode(wire?PolygonMode.WIREFRAME:PolygonMode.FILL).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,depthWrite)).build());
    }
    private static RenderPipeline planePipeline(boolean wire) {
        return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.parse(wire?"faraway:pipeline/plane_wire":"faraway:pipeline/plane"))
            .withVertexShader(Identifier.parse("faraway:core/plane")).withFragmentShader(Identifier.parse("faraway:core/plane"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("FarAwayCoverage",UniformType.UNIFORM_BUFFER).build())
            .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0_SAMPLER2)
            .withPolygonMode(wire?PolygonMode.WIREFRAME:PolygonMode.FILL).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true)).build());
    }
    private static final com.mojang.renderpearl.api.vertex.VertexFormat QUAD_FORMAT=com.mojang.renderpearl.api.vertex.VertexFormat.builder(1)
        .addAttribute("Origin",com.mojang.renderpearl.api.GpuFormat.RGB32_FLOAT)
        .addAttribute("ExtentU",com.mojang.renderpearl.api.GpuFormat.R32_FLOAT)
        .addAttribute("ExtentV",com.mojang.renderpearl.api.GpuFormat.R32_FLOAT)
        .addAttribute("Axes",com.mojang.renderpearl.api.GpuFormat.R32_UINT)
        .addAttribute("ColorA",com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM)
        .addAttribute("ColorB",com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM)
        .addAttribute("ColorC",com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM)
        .addAttribute("ColorD",com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM).build();
    private static final RenderPipeline QUADS=quadPipeline(false,true,true),QUADS_NO_CULL=quadPipeline(false,false,true),
        QUADS_WIRE=quadPipeline(true,true,true),QUADS_WIRE_NO_CULL=quadPipeline(true,false,true),
        QUADS_PAINT=quadPipeline(false,true,false),QUADS_PAINT_NO_CULL=quadPipeline(false,false,false);
    private static RenderPipeline quadPipeline(boolean wire,boolean cull,boolean depthWrite) {
        return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.parse("faraway:pipeline/quads_"+wire+"_"+cull+"_"+depthWrite))
            .withVertexShader(Identifier.parse("faraway:core/terrain_quad")).withFragmentShader(Identifier.parse("faraway:core/terrain"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("FarAwayCoverage",UniformType.UNIFORM_BUFFER).build())
            .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0_SAMPLER2)
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("FarAwayMaterials",UniformType.UNIFORM_BUFFER).build())
            .withVertexBinding(0,QUAD_FORMAT).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withPolygonMode(wire?PolygonMode.WIREFRAME:PolygonMode.FILL).withCull(cull)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,depthWrite)).build());
    }
    private static final RenderPipeline GEN_PREVIEW=RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation(Identifier.parse("faraway:pipeline/gen_preview"))
        .withVertexShader(Identifier.parse("faraway:core/gen_preview"))
        .withFragmentShader(Identifier.parse("faraway:core/gen_preview"))
        .withPrimitiveTopology(PrimitiveTopology.DEBUG_LINES).withCull(false)
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,false)).build());
    private static final RenderPipeline FRUSTUM_EDGES_OVERLAY=RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation(Identifier.parse("faraway:pipeline/frustum_edges_overlay"))
        .withVertexShader(Identifier.parse("faraway:core/gen_preview"))
        .withFragmentShader(Identifier.parse("faraway:core/gen_preview"))
        .withPrimitiveTopology(PrimitiveTopology.DEBUG_LINES).withCull(false)
        .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS,false)).build());
    private static final RenderPipeline FRUSTUM_ARROW=RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation(Identifier.parse("faraway:pipeline/frustum_arrow"))
        .withVertexShader(Identifier.parse("faraway:core/gen_preview"))
        .withFragmentShader(Identifier.parse("faraway:core/gen_preview"))
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
        .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS,false)).build());
    private static RenderPipeline pipeline(boolean wireframe,boolean water,boolean grid) {return pipeline(wireframe,water,grid,true);}
    private static RenderPipeline pipeline(boolean wireframe,boolean water,boolean grid,boolean cull) {return pipeline(wireframe,water,grid,cull,true);}
    private static RenderPipeline pipeline(boolean wireframe,boolean water,boolean grid,boolean cull,boolean depthWrite) {
        return pipeline(wireframe,water,grid,cull,depthWrite,false);
    }
    private static RenderPipeline pipeline(boolean wireframe,boolean water,boolean grid,boolean cull,boolean depthWrite,boolean seam) { var builder=
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.parse(seam?(cull?"faraway:pipeline/vanilla_seam_cull":"faraway:pipeline/vanilla_seam"):(water?"faraway:pipeline/water":grid?(wireframe?"faraway:pipeline/grid_wireframe":"faraway:pipeline/grid_terrain"):wireframe?"faraway:pipeline/wireframe":"faraway:pipeline/terrain")+(cull?"":"_no_cull")+(depthWrite?"":"_paint")))
            .withVertexShader(Identifier.parse(grid?"faraway:core/terrain_grid":"faraway:core/terrain"))
            .withFragmentShader(Identifier.parse(water?"faraway:core/water":"faraway:core/terrain"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("FarAwayCoverage",UniformType.UNIFORM_BUFFER).build())
            .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0_SAMPLER2)
            .withBindGroupLayout(water?BindGroupLayout.builder().build():BindGroupLayout.builder().withUniform("FarAwayMaterials",UniformType.UNIFORM_BUFFER).build())
            .withPolygonMode(wireframe?PolygonMode.WIREFRAME:PolygonMode.FILL)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(cull)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(new DepthStencilState(seam?CompareOp.GREATER_THAN:CompareOp.GREATER_THAN_OR_EQUAL, depthWrite));
        if(grid) builder.withVertexBinding(0,com.mojang.renderpearl.api.vertex.VertexFormat.builder(0)
            .addAttribute("PackedPosition",com.mojang.renderpearl.api.GpuFormat.R32_UINT)
            .addAttribute("Color",com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM).build());
        return RenderPipelines.register(builder.build()); }
    private final HiZ hiZ=new HiZ();
    private final java.util.IdentityHashMap<GpuTile,Integer> hiZIds=new java.util.IdentityHashMap<>();
    private final java.util.Set<GpuTile> occluders=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final ArrayList<AABB> hiZBounds=new ArrayList<>();
    private final java.util.PriorityQueue<GpuTile> nearestOccluders=new java.util.PriorityQueue<>(64,java.util.Comparator.comparingDouble((GpuTile t)->t.occluderDistance).reversed());
    private boolean hiZResources;
    private boolean occluderPass,skipOccluders,deferOutlines;
    private com.mojang.blaze3d.pipeline.RenderTarget anchorDepth;
    private net.minecraft.client.renderer.MappableRingBuffer anchorProjection;
    private ByteBudget.Reservation anchorDepthLease;
    private long hiZFrames,hiZCpuNanos,hiZNextLog;
    private final GpuPassProfile gpuProfile=new GpuPassProfile();
    private final java.util.ArrayDeque<Runnable> deferredPlaneRetirements=new java.util.ArrayDeque<>();
    private long nextWorkloadSample;
    private int sampledActiveWorkers,sampledUploadQueue,sampledBuildQueue,sampledInFlight;
    private final AtomicReference<Pending> pending = new AtomicReference<>();
    private final List<GpuTile> tiles = new ArrayList<>();
    private final ArrayList<GpuTile> visibleScratch=new ArrayList<>();
    private boolean visibleCacheValid;
    private boolean visibleCacheFrustumCulling;
    private final Matrix4f visibleCacheMatrix=new Matrix4f();
    private double visibleCacheX,visibleCacheY,visibleCacheZ;
    private int visibleCacheMinLod,visibleCacheMaxLod;
    private long visibleCacheTriangles,visibleCacheSamples;
    private volatile long epoch;
    private long uploadedEpoch;
    private volatile String status = "preview disabled";
    private final ByteBudget gpuMemory = new ByteBudget(1024L*1024*1024);
    private final GeometryArena geometryArena=new GeometryArena(gpuMemory);
    private final java.util.LinkedHashSet<GeometryArena.Page> geometryPages=new java.util.LinkedHashSet<>();
    private volatile TileCoordinator streaming;
    private volatile int radiusChunks=256;
    private TileCoordinator.Completed heldUpload;
    private volatile boolean resourceLimit;
    private final java.util.concurrent.atomic.AtomicLong retiringBytes=new java.util.concurrent.atomic.AtomicLong();
    private volatile int drawnTiles;
    private volatile int hudResidentTiles;
    private volatile long residentTriangles,drawnTriangles;
    private long activeTriangles,frustumTriangles,backfaceTriangles,frameDrawCalls,cachedActiveTriangles,cachedFrustumTriangles;
    private final dev.faraway.k7q4m9.diagnostics.DrawCallWindow drawCallWindow=new dev.faraway.k7q4m9.diagnostics.DrawCallWindow();
    private final dev.faraway.k7q4m9.diagnostics.DrawCallWindow drawCallWindow10=new dev.faraway.k7q4m9.diagnostics.DrawCallWindow(10_000_000_000L);
    public long drawCallsLast10Seconds() {return drawCallWindow10.sum(System.nanoTime());}
    public long drawCallsPerSecond() { return drawCallWindow.sum(System.nanoTime()); }
    private long prePlaneTiles,preVoxelTiles;
    public long prePlaneTiles() {return prePlaneTiles;}
    public long preVoxelTiles() {return preVoxelTiles;}
    public record TriangleStages(long active,long frustum,long backface,long calls) {}
    public TriangleStages triangleStages() {return new TriangleStages(activeTriangles,frustumTriangles,backfaceTriangles,frameDrawCalls);}
    private volatile long residentSamples,displayedSamples,renderedSamples;
    private volatile LodTimingStats.Summary[] residentLodTimings=new LodTimingStats.Summary[0];
    private volatile long lastUploadNanos;
    private final VanillaCoverage vanillaCoverage=new VanillaCoverage();
    private CoverageBuffer coverageBuffer,emptyCoverageBuffer;
    private ByteBuffer lastCoverage;
    private dev.faraway.k7q4m9.streaming.WorkingSetEstimate workingSet;
    private CoverageBuffer preparedCoverage;
    private VanillaSeam vanillaSeam;
    private it.unimi.dsi.fastutil.longs.LongOpenHashSet patchedSeamChunks=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private List<VanillaCoverage.Edge> seamEdges=List.of();
    private List<GpuTile> seamSources=List.of();
    private java.util.Set<TileKey> seamDrawn;
    private boolean seamDirty=true,seamColored;
    private int seamMinLod=-1,seamMaxLod=-1;
    private GpuBuffer materialBuffer;
    private final BlockTextureFiltering blockTextureFiltering=new BlockTextureFiltering();
    private final MipmappedBlockAtlas mipmappedBlockAtlas=new MipmappedBlockAtlas();
    private GpuBuffer frustumEdgeLines;
    private ByteBudget.Reservation frustumEdgeLease;
    private final ArrayList<GpuTile> frustumEdgeSelection=new ArrayList<>();
    private List<GpuTile> uploadedFrustumEdges=List.of();
    private List<TileKey> frustumEdgeVisibleKeys=List.of();
    private final ArrayList<TileKey> frustumEdgeVisibleScratch=new ArrayList<>();
    private TileFootprintEdges frustumEdgeNeighbours;
    private int frustumEdgeCount,uploadedFrustumEdgeSeaLevel;
    private double frustumEdgeOriginX,frustumEdgeOriginZ;
    private GpuBuffer previewLines;
    private ByteBudget.Reservation previewLineLease;
    private Object uploadedPreviewSquares,uploadedPreviewEdges;
    private int uploadedPreviewMode=-1,uploadedPreviewMin=-1,uploadedPreviewMax=-1,previewLineCount;
    private double previewAnchorX,previewAnchorZ;
    private GpuBuffer planeVertices;
    private GpuBuffer frustumLines,frustumArrow;
    private net.minecraft.client.renderer.MappableRingBuffer planeBatches;
    private ByteBudget.Reservation planeBatchReservation;
    private boolean planeInstancingUnavailable;
    private boolean planeBatchesUsed;
    private int planeBatchCapacity,planeBatchStride;
    private long planeLastUsed,planeUnderusedSince,planePipelineRetryAt,planeBufferRetryAt;
    private final java.util.IdentityHashMap<PlaneAtlas.Page,PlaneGroup> planeGroups=new java.util.IdentityHashMap<>();
    private final java.util.ArrayList<PlaneGroup> activePlaneGroups=new java.util.ArrayList<>();
    private int frustumLineVertices,frustumFrameVertices;
    private dev.faraway.k7q4m9.client.FreecamFrustum.Shape uploadedFrustum;
    private int previewSeaLevel=63;
    private final dev.faraway.k7q4m9.streaming.CoveragePlanner previewCoverage=new dev.faraway.k7q4m9.streaming.CoveragePlanner();
    private dev.faraway.k7q4m9.streaming.LodPlanningSession previewPlanning=new dev.faraway.k7q4m9.streaming.LodPlanningSession();
    private Object previewAccepted;
    private java.util.Set<TileKey> previewDisplayed=java.util.Set.of();
    private dev.faraway.k7q4m9.streaming.GenPreviewPlanner previewPlanner=new dev.faraway.k7q4m9.streaming.GenPreviewPlanner();
    private dev.faraway.k7q4m9.streaming.LodScatter.Config previewScatter;
    private volatile java.util.List<dev.faraway.k7q4m9.streaming.GenPreviewPlanner.Square> previewSquares=java.util.List.of();
    private volatile java.util.List<dev.faraway.k7q4m9.streaming.PreviewEdges.Edge> previewEdges=java.util.List.of();
    private int previewWorldMax;
    private int previewMode=-1,previewMinLod=-1,previewMaxLod=-1;
    private boolean previewPlannedLods,uploadedPlannedLods;
    private long nextPreviewPlanNanos,tickProfileStart,tickPlanNs,tickPlanMaxNs,tickPreviewNs,tickPreviewMaxNs,tickProfileCount;
    private int previewX,previewY,previewZ,previewRadius,previewHeight,previewBudget;
    private double previewFov,previewPixels;
    private com.mojang.renderpearl.api.textures.GpuTextureView materialAtlasView;
    private int materialRevision=-1;
    private long preparedEpoch;
    private long nextStatusNanos;
    private long nextTimingSnapshotNanos,renderProfileStart,profileFrames,profilePrepareFrames,profileCoverageNs,profileCoverageMaxNs,profileRenderNs,profileRenderMaxNs,profileUploadNs,profileUploadMaxNs;
    private long profileTerrainNs,profileTerrainMaxNs,profileWaterNs,profileWaterMaxNs;
    private long profilePlaneBuildNs,profilePlaneDrawNs,profilePlaneMaxNs,profilePlaneTiles,profilePlanePages,profilePlaneCalls,profilePlaneFallback,profilePlaneBytes;

    // Upload before LevelRenderer opens its pass; draw inside its existing opaque pass.
    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }
    public long reset() {
        releaseFrustumEdges();hiZ.counters.close();gpuProfile.reset(); nextWorkloadSample=0;
        for(var page:geometryPages)page.visible.clear();geometryPages.clear();visibleScratch.clear();
        retirePlaneBuffer(); planeGroups.clear(); activePlaneGroups.clear();
        planeInstancingUnavailable=false; planePipelineRetryAt=planeBufferRetryAt=0;
        pending.set(null);
        previewSquares=java.util.List.of();
        previewEdges=java.util.List.of();
        previewScatter=null;
        nextPreviewPlanNanos=tickProfileStart=tickPlanNs=tickPlanMaxNs=tickPreviewNs=tickPreviewMaxNs=tickProfileCount=0;
        nextTimingSnapshotNanos=renderProfileStart=profileFrames=profilePrepareFrames=profileCoverageNs=profileCoverageMaxNs=profileRenderNs=profileRenderMaxNs=profileUploadNs=profileUploadMaxNs=0;
        profileTerrainNs=profileTerrainMaxNs=profileWaterNs=profileWaterMaxNs=0;
        profilePlaneBuildNs=profilePlaneDrawNs=profilePlaneMaxNs=profilePlaneTiles=profilePlanePages=profilePlaneCalls=profilePlaneFallback=profilePlaneBytes=0;
        activeTriangles=frustumTriangles=backfaceTriangles=frameDrawCalls=prePlaneTiles=preVoxelTiles=0;
        dev.faraway.k7q4m9.diagnostics.AdvancedRenderTiming.reset();
        drawCallWindow.clear();drawCallWindow10.clear();
        hudResidentTiles=0; residentTriangles=0; drawnTiles=0; drawnTriangles=0; residentSamples=displayedSamples=renderedSamples=0;
        residentLodTimings=new LodTimingStats.Summary[0];
        vanillaCoverage.reset(); lastCoverage=null; preparedCoverage=null;
        releaseVanillaSeam();
        previewPlanning.close();previewPlanning=new dev.faraway.k7q4m9.streaming.LodPlanningSession();previewAccepted=null;
        TileCoordinator old=streaming; streaming=null;
        if(old!=null) old.close();
        if(heldUpload!=null) { heldUpload.close(); heldUpload=null; }
        retire(); // World exit must release meshes even if no further level frame is drawn.
        FarAwayView.distanceBlocks=0;
        resourceLimit=false; status = "preview disabled"; return ++epoch;
    }
    public void startStreaming(WorldDefinition world,int radius) {
        if(radius<1) throw new IllegalArgumentException("Radius must be positive");
        reset(); setRadius(radius);
        gpuMemory.setLimit(dev.faraway.k7q4m9.client.LodSettings.toBytes(dev.faraway.k7q4m9.client.LodSettings.effectiveVramGiB()));
        try {
            var camera=Minecraft.getInstance().gameRenderer.mainCamera().blockPosition();
            workingSet=dev.faraway.k7q4m9.streaming.WorkingSetEstimate.plan(camera.getX(),camera.getZ(),radius,4096,gpuMemory.limit(),VanillaCoverage.BYTES*2L);
            if(!workingSet.minimumFits()) throw new IllegalArgumentException("Required vertex buffers alone exceed GPU budget");
        } catch(IllegalArgumentException rejected) {
            resourceLimit=true; status="Preflight refused: "+rejected.getMessage();
            dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.warn("{}",status); return;
        }
        gpuMemory.resetPeak(); streaming=new TileCoordinator(world);
        previewSeaLevel=world.generator().getSeaLevel();
        dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.info("Streaming working set estimate: {}",workingSet);
        FarAwayView.distanceBlocks=TileKey.WORLD ? 90_000_000 : Math.addExact(Math.multiplyExact(radius,16),512);
        status="streaming requested radiusChunks="+radius;
    }
    public void setRadius(int radius) {
        if(radius<1 || radius>6_000_000) throw new IllegalArgumentException("Invalid radius");
        radiusChunks=radius;
        dev.faraway.k7q4m9.client.LodSettings.setRenderDistance(radius);
    }
    public boolean isStreaming() { return streaming!=null; }
    public boolean benchmarkCoverageReady() { var active=streaming; return active!=null && active.benchmarkCoverageReady(); }
    public void setPlaneThreshold(int threshold) { var active=streaming; if(active!=null) active.setPlaneThreshold(threshold); }
    public int[] benchmarkBoundaryProgress() { var active=streaming; return active==null?new int[2]:active.benchmarkBoundaryProgress(); }
    public int activeWorkerLimit() { var active=streaming; return active==null?LodSettings.activeWorkers():active.activeWorkerLimit(); }
    public TileCoordinator.PoolSnapshot tileGenPoolSnapshot() {
        var active=streaming;
        if(active!=null) return active.poolSnapshot();
        var options=LodSettings.get().generation();
        long heap=LodSettings.toBytes(LodSettings.effectiveHeapGiB());
        return new TileCoordinator.PoolSnapshot(0,LodSettings.activeWorkers()*options.poolMultiplier(),0,
            Math.min(options.effectivePoolBytes(heap),heap/2));
    }
    public double benchmarkRemainingSeconds() { var active=streaming; return active==null?Double.NaN:active.benchmarkRemainingSeconds(); }
    public boolean benchmarkPlanTooLarge() { var active=streaming; return active!=null && active.benchmarkPlanTooLarge(); }
    public dev.faraway.k7q4m9.streaming.WorkingSetEstimate workingSet() { return workingSet; }
    public boolean prefetchReady() { var active=streaming; return active!=null && active.prefetchReady(); }
    private void tick(Minecraft client) {
        submitPlaneRetirements();
        var settings=dev.faraway.k7q4m9.client.LodSettings.get();
        gpuMemory.setLimit(dev.faraway.k7q4m9.client.LodSettings.toBytes(dev.faraway.k7q4m9.client.LodSettings.effectiveVramGiB()));
        var active=streaming;
        if(active==null || client.level==null || client.player==null) {updateGenPreview(client,null);FarAwayView.distanceBlocks=settings.genPreview()&&client.level!=null?90_000_000:0;return;}
        radiusChunks=dev.faraway.k7q4m9.client.LodSettings.get().renderDistance();
        var camera=dev.faraway.k7q4m9.client.Freecam.planPosition(client.gameRenderer.mainCamera().blockPosition());
        active.setProjection(LodSettings.get().screenSpaceLod()?dev.faraway.k7q4m9.client.Freecam.lodProjection():null);
        // User-selected throughput mode uses every available logical processor.
        long tickStart=System.nanoTime();
        // The coordinator can change drawable transition coverage on every tick.
        visibleCacheValid=false;
        try { active.update(camera.getX(),camera.getY(),camera.getZ(),radiusChunks,4096,client.getFps(),
            dev.faraway.k7q4m9.client.Freecam.planHeight(client.getWindow().getHeight()),dev.faraway.k7q4m9.client.Freecam.planFov(client.gameRenderer.mainCamera().getFov()),dev.faraway.k7q4m9.client.LodSettings.get().samplePixels()); }
        catch(IllegalArgumentException limit) { resourceLimit=true; status=limit.getMessage(); }
        long planElapsed=System.nanoTime()-tickStart;
        updateGenPreview(client,active);
        long previewElapsed=System.nanoTime()-tickStart-planElapsed;
        tickPlanNs+=planElapsed; tickPlanMaxNs=Math.max(tickPlanMaxNs,planElapsed);
        tickPreviewNs+=previewElapsed; tickPreviewMaxNs=Math.max(tickPreviewMaxNs,previewElapsed); tickProfileCount++;
        if(tickProfileStart==0) tickProfileStart=tickStart;
        if(tickStart-tickProfileStart>=10_000_000_000L) {
            dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.info("FarAway tick CPU profile ticks={} coordinatorAvgMs={} coordinatorMaxMs={} previewAvgMs={} previewMaxMs={} camera={},{},{}",
                tickProfileCount,tickPlanNs/1_000_000.0/tickProfileCount,tickPlanMaxNs/1_000_000.0,tickPreviewNs/1_000_000.0/tickProfileCount,tickPreviewMaxNs/1_000_000.0,camera.getX(),camera.getY(),camera.getZ());
            dev.faraway.k7q4m9.diagnostics.TileLogSummary.flush();
            tickProfileStart=tickStart; tickPlanNs=tickPlanMaxNs=tickPreviewNs=tickPreviewMaxNs=tickProfileCount=0;
        }
    }
    private void updateGenPreview(Minecraft client,TileCoordinator active) {
        var settings=dev.faraway.k7q4m9.client.LodSettings.get();
        if(!settings.genPreview() || client.level==null || client.player==null) { previewSquares=java.util.List.of(); previewEdges=java.util.List.of(); previewAccepted=null; nextPreviewPlanNanos=0; return; }
        var scatter=new dev.faraway.k7q4m9.streaming.LodScatter.Config(settings.scatterStrength(),settings.scatterScale(),settings.scatterSeedOffset(),settings.noiseBottom(),settings.noiseTop(),settings.scatterMode(),settings.noiseEnabled());
        if(!scatter.equals(previewScatter)) {
            previewScatter=scatter;
            previewPlanner=new dev.faraway.k7q4m9.streaming.GenPreviewPlanner(scatter);
            nextPreviewPlanNanos=0;
        }
        var camera=dev.faraway.k7q4m9.client.Freecam.planPosition(client.gameRenderer.mainCamera().blockPosition());
        int budget=(int)Math.min(Integer.MAX_VALUE,Math.min(dev.faraway.k7q4m9.client.LodSettings.toBytes(LodSettings.effectiveHeapGiB())/(64L*1024),dev.faraway.k7q4m9.client.LodSettings.toBytes(LodSettings.effectiveVramGiB())/dev.faraway.k7q4m9.streaming.WorkingSetEstimate.TERRAIN_BYTES));
        long now=System.nanoTime(); int screenHeight=dev.faraway.k7q4m9.client.Freecam.planHeight(client.getWindow().getHeight()); double fov=dev.faraway.k7q4m9.client.Freecam.planFov(client.gameRenderer.mainCamera().getFov());
        try {
            dev.faraway.k7q4m9.streaming.DirectLodPlanner.Plan shared;
            if(active!=null) shared=active.acceptedPlan();
            else {
                var roots=previewCoverage.update(camera.getX(),camera.getZ(),TileKey.WORLD?1:settings.renderDistance(),Math.max(64,budget));
                var request=new dev.faraway.k7q4m9.streaming.LodPlanningSession.Request(roots,camera.getX(),camera.getY(),camera.getZ(),client.level.getMinY(),client.level.getMaxY(),screenHeight,fov,settings.samplePixels(),budget,settings.renderDistance()*16.0,scatter,settings.screenSpaceLod()?dev.faraway.k7q4m9.client.Freecam.lodProjection():null,settings.lodLowerTolerance(),settings.lodUpperTolerance());
                shared=previewPlanning.update(request,settings.asyncLodPlanning(),settings.lodPlanHz(),false);
            }
            if(shared==null) { previewSquares=java.util.List.of();previewEdges=java.util.List.of();return; }
            var displayed=active==null?java.util.Set.<TileKey>of():active.displayedTiles();
            if(shared==previewAccepted && (!settings.showPlannedLods() || displayed.equals(previewDisplayed)) && settings.showPlannedLods()==previewPlannedLods && settings.previewMode()==previewMode && settings.minLodVisible()==previewMinLod && settings.maxLodVisible()==previewMaxLod)return;
            previewAccepted=shared;previewDisplayed=displayed;
            var planned=shared.leaves().stream().map(key -> new dev.faraway.k7q4m9.streaming.GenPreviewPlanner.Square(key,Integer.numberOfTrailingZeros(key.spacing()))).toList();
            previewSquares=!settings.showPlannedLods()?java.util.List.of():active!=null?dev.faraway.k7q4m9.streaming.GenPreviewPlanner.pendingOnly(planned,displayed):planned;
            previewEdges=settings.previewMode()>=2?dev.faraway.k7q4m9.streaming.PreviewEdges.build(planned,settings.minLodVisible(),settings.maxLodVisible()):java.util.List.of();
            previewPlannedLods=settings.showPlannedLods();previewMode=settings.previewMode(); previewMinLod=settings.minLodVisible(); previewMaxLod=settings.maxLodVisible(); previewWorldMax=client.level.getMaxY();
            previewSeaLevel=client.level.getSeaLevel();
            previewX=camera.getX(); previewY=camera.getY(); previewZ=camera.getZ(); previewRadius=settings.renderDistance(); previewHeight=screenHeight; previewBudget=budget; previewFov=fov; previewPixels=settings.samplePixels();
            nextPreviewPlanNanos=now+(long)(1_000_000_000.0/settings.lodPlanHz());
        } catch(IllegalArgumentException invalid) { previewSquares=java.util.List.of(); previewEdges=java.util.List.of(); }
    }
    public long epoch() { return epoch; }
    public String status() { return status+" vanillaReadyColumns="+vanillaCoverage.readyColumns(); }
    public void publish(long token, List<TileMesh> meshes) {
        if (token == epoch) { pending.set(new Pending(token,List.copyOf(meshes))); status = "preview upload pending"; }
    }
    public void prepareFrame() {
        long start=System.nanoTime();
        try { prepareFrameContents(); }
        finally {
            RenderProfile.INSTANCE.add(PREPARE,System.nanoTime()-start);
            var active=streaming;
            if(active!=null && start>=nextWorkloadSample) {
                sampledActiveWorkers=active.runningWorkers(); sampledUploadQueue=active.queuedUploads();
                sampledBuildQueue=active.queuedBuilds(); sampledInFlight=active.inFlightBuilds();
                nextWorkloadSample=start+100_000_000L;
            }
            RenderProfile.INSTANCE.workload(LodSettings.get().enabled(),active==null?0:sampledActiveWorkers,
                active==null?0:active.activeWorkerLimit(),active==null?0:sampledUploadQueue,active==null?0:active.pendingBytes(),
                active==null?0:sampledBuildQueue,active==null?0:sampledInFlight);
        }
    }
    public void tickBlockTextures() { mipmappedBlockAtlas.tick(); }

    private net.minecraft.client.renderer.texture.TextureAtlas blockAtlas() {
        var shared=(net.minecraft.client.renderer.texture.TextureAtlas)Minecraft.getInstance().getTextureManager()
            .getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        return mipmappedBlockAtlas.select(shared);
    }

    private void prepareFrameContents() {
        mipmappedBlockAtlas.update();
        geometryArena.beginFrame();
        submitPlaneRetirements();
        preparePreviewLines();
        if(planeBatches!=null && planeBatchesUsed) { planeBatches.rotate(); planeBatchesUsed=false; }
        if(planeBatches!=null && System.nanoTime()-planeLastUsed>=5_000_000_000L) retirePlaneBuffer();
        if(planeVertices==null && LodSettings.get().renderTiles()) {
            var data=ByteBuffer.allocateDirect(6*16).order(java.nio.ByteOrder.nativeOrder());
            for(int[] p:new int[][]{{0,0},{0,1},{1,0},{1,0},{0,1},{1,1}}) data.putFloat(p[0]).putFloat(0).putFloat(p[1]).putInt(-1);
            data.flip(); planeVertices=GpuTile.upload(data,GpuBuffer.USAGE_VERTEX);
        }
        if (uploadedEpoch != epoch) { retire(); uploadedEpoch = epoch; }
        Pending ready = pending.getAndSet(null);
        if (ready != null && ready.epoch == epoch) {
            retire();
            seamDirty=true;
            try {
                for (TileMesh mesh : ready.meshes) {
                    var lease=gpuMemory.tryReserve(1);
                    if(lease==null) throw new IllegalStateException("GPU byte budget exhausted");
                    tiles.add(new GpuTile(mesh,lease,-1,gpuMemory,geometryArena));
                }
                status = "preview resident=" + tiles.size() + " gpuBytes=" + tiles.stream().mapToLong(t -> t.bytes).sum();
            } catch (RuntimeException failure) {
                retire(); status = "preview upload failed: " + failure.getMessage();
                dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.error("Preview upload failed", failure);
            }
        }
        var active=streaming;
        if(active!=null) {
            var removed=new ArrayList<GpuTile>();
            tiles.removeIf(tile -> { if(!active.retained(tile.key)) { removed.add(tile); return true; } return false; });
            if(!removed.isEmpty()) visibleCacheValid=false;
            retire(removed);
            long uploadStart=System.nanoTime();
            uploadStreaming(active);
            long uploadElapsed=System.nanoTime()-uploadStart;
            profileUploadNs+=uploadElapsed; profileUploadMaxNs=Math.max(profileUploadMaxNs,uploadElapsed);
            if(!resourceLimit && System.nanoTime()>=nextStatusNanos) {
                nextStatusNanos=System.nanoTime()+250_000_000L;
                status="streaming radiusChunks="+radiusChunks+" resident="+active.residentCount()+"/"+active.wanted().size()
                +" "+active.productionStatus()+" retained="+active.retainedCount()+" gpuBytes="+gpuMemory.used()+" retiringBytes="+retiringBytes.get()+" pendingBytes="+active.pendingBytes()+" failed="+active.failures();
            }
        }
        hudResidentTiles=tiles.size(); residentTriangles=tiles.stream().mapToLong(GpuTile::triangles).sum();
        residentSamples=0; displayedSamples=0;
        var sampleSettings=LodSettings.get();
        long timingNow=System.nanoTime();
        var timings=sampleSettings.tileDetailHud() && timingNow>=nextTimingSnapshotNanos?new LodTimingStats():null;
        var counted=timings==null?null:new java.util.HashSet<TileKey>();
        for(var tile:tiles) {
            long samples=horizontalSamples(tile.key); residentSamples+=samples;
            if(timings!=null && tile.generationNanos>=0 && counted.add(tile.key))
                timings.record(tile.key.spacing(),samples,tile.generationNanos);
            int lod=Integer.numberOfTrailingZeros(tile.key.spacing());
            if(lod>=sampleSettings.minLodVisible() && lod<=sampleSettings.maxLodVisible() && (streaming==null || streaming.drawable(tile.key))) displayedSamples+=samples;
        }
        if(timings!=null) {
            if(heldUpload!=null) heldUpload.recordLodTiming(timings,counted);
            if(active!=null) active.recordQueuedLodTimings(timings,counted);
            residentLodTimings=timings.snapshot();
            nextTimingSnapshotNanos=timingNow+1_000_000_000L;
        } else if(!sampleSettings.tileDetailHud() && residentLodTimings.length!=0) {
            residentLodTimings=new LodTimingStats.Summary[0];
            nextTimingSnapshotNanos=0;
        }
        activeTriangles=frustumTriangles=backfaceTriangles=frameDrawCalls=prePlaneTiles=preVoxelTiles=0;
        drawnTiles=0; drawnTriangles=0; renderedSamples=0; preparedEpoch=epoch;
        if(!sampleSettings.renderTiles()) {
            visibleCacheValid=false;visibleScratch.clear();releaseVanillaSeam();
            return;
        }
        long coverageStart=System.nanoTime();
        if(!tiles.isEmpty() && (!dev.faraway.k7q4m9.client.Freecam.debugCamActive() || preparedCoverage==null)) {
            vanillaCoverage.update(Minecraft.getInstance());
        }
        prepareVanillaSeam(sampleSettings);
        preparedCoverage=tiles.isEmpty()?null:updateCoverageBuffer();
        long coverageElapsed=System.nanoTime()-coverageStart;
        RenderProfile.INSTANCE.add(COVERAGE,coverageElapsed);
        profileCoverageNs+=coverageElapsed; profileCoverageMaxNs=Math.max(profileCoverageMaxNs,coverageElapsed);
        profilePrepareFrames++;
    }
    /** Runs between the closed vanilla solid pass and subsequent transparency passes. */
    public void renderDeferred(com.mojang.blaze3d.pipeline.RenderTarget target,CameraRenderState camera) {
        var settings=LodSettings.get();hiZ.paintEnabled=settings.hiZPaint();hiZ.counters.setPollRate(settings.hiZDataPollRate());
        if(!LodSettings.hiZActive()) { releaseHiZ();renderFrustumCullingEdges(target,camera);return; }
        dev.faraway.k7q4m9.client.Freecam.captureView(camera);
        boolean enabled=settings.renderTiles()&&settings.drawCalls()&&!settings.wireframe()&&hiZ.supported()&&preparedCoverage!=null&&!tiles.isEmpty();
        if(!settings.renderTiles()||!settings.drawCalls()||settings.wireframe())releaseHiZ();
        var source=target;
        var selection=camera;
        boolean freecam=dev.faraway.k7q4m9.client.Freecam.debugCamActive();
        occluders.clear();hiZIds.clear();
        long start=System.nanoTime();
        try {
            if(enabled) {try {
                if(!freecam)releaseAnchorDepth();
                selection=new CameraRenderState();
                selection.pos=dev.faraway.k7q4m9.client.Freecam.anchorOr(camera.pos);
                selection.projectionMatrix.set(dev.faraway.k7q4m9.client.Freecam.projectionOr(camera.projectionMatrix));
                selection.viewRotationMatrix.set(dev.faraway.k7q4m9.client.Freecam.viewOr(camera.viewRotationMatrix));
                selection.cullFrustum=camera.cullFrustum;selection.fogData=camera.fogData;
                hiZResources=true;nearestOccluders.clear();hiZBounds.clear();
                for(var tile:tiles){hiZIds.put(tile,hiZBounds.size());hiZBounds.add(tile.bounds);}
                long[] samples=null;
                if((settings.triangleHudMode()!=LodSettings.TriangleHudMode.OFF || settings.hiZDetailsHud()) && hiZ.counters.due()) {
                    samples=new long[tiles.size()];
                    for(int i=0;i<tiles.size();i++) samples[i]=horizontalSamples(tiles.get(i).key);
                }
                hiZ.counters.begin(hiZ,samples,gpuMemory);
                if(samples!=null)for(int i=0;i<tiles.size();i++)if(tiles.get(i).plane!=null)hiZ.counters.plane(i);
                var selectionMatrix=new Matrix4f(selection.projectionMatrix).mul(selection.viewRotationMatrix);
                // Reserve half of the occluder budget for screen coverage. Nearest-only
                // selection concentrates depth in the foreground and misses distant ridges.
                GpuTile[] screenOccluders=new GpuTile[32];
                for(var tile:tiles)if((tile.sharedTerrain!=null||tile.sharedQuads!=null)&&!vanillaCoverage.covers(tile.bounds)
                    &&Integer.numberOfTrailingZeros(tile.key.spacing())>=settings.minLodVisible()&&Integer.numberOfTrailingZeros(tile.key.spacing())<=settings.maxLodVisible()
                    &&(streaming==null||streaming.drawable(tile.key))
                    &&TileVisibility.intersectsScreen(tile.bounds,selection.pos.x,selection.pos.y,selection.pos.z,selectionMatrix)) {
                    tile.occluderDistance=tile.bounds.distanceToSqr(selection.pos);
                    if(nearestOccluders.size()<32)nearestOccluders.add(tile);
                    else if(tile.occluderDistance<nearestOccluders.peek().occluderDistance){nearestOccluders.poll();nearestOccluders.add(tile);}
                    double cx=(tile.bounds.minX+tile.bounds.maxX)*.5-selection.pos.x,cy=(tile.bounds.minY+tile.bounds.maxY)*.5-selection.pos.y,cz=(tile.bounds.minZ+tile.bounds.maxZ)*.5-selection.pos.z;
                    double w=selectionMatrix.m03()*cx+selectionMatrix.m13()*cy+selectionMatrix.m23()*cz+selectionMatrix.m33();
                    if(w>0.0001){
                        double sx=((selectionMatrix.m00()*cx+selectionMatrix.m10()*cy+selectionMatrix.m20()*cz+selectionMatrix.m30())/w*.5+.5);
                        double sy=((selectionMatrix.m01()*cx+selectionMatrix.m11()*cy+selectionMatrix.m21()*cz+selectionMatrix.m31())/w*.5+.5);
                        if(sx>=0&&sx<1&&sy>=0&&sy<1){int bin=(int)(sy*4)*8+(int)(sx*8);var old=screenOccluders[bin];if(old==null||tile.occluderDistance<old.occluderDistance)screenOccluders[bin]=tile;}
                    }
                }
                occluders.addAll(nearestOccluders);for(var tile:screenOccluders)if(tile!=null)occluders.add(tile);
                if(freecam) {
                    ensureAnchorDepth(target.getDepthTexture().getWidth(0),target.getDepthTexture().getHeight(0));source=anchorDepth;
                    var encoder=RenderSystem.getDevice().createCommandEncoder();
                    try(var map=anchorProjection.currentBuffer().slice().map(false,true)) {selection.projectionMatrix.get(0,map.data());}encoder.submit();
                    RenderSystem.backupProjectionMatrix();
                    RenderSystem.setProjectionMatrix(anchorProjection.currentBuffer().slice(),com.mojang.blaze3d.ProjectionType.PERSPECTIVE);
                }
                occluderPass=true;hiZ.counters.suppressed=freecam;visibleCacheValid=false;
                var encoder=RenderSystem.getDevice().createCommandEncoder();
                try(var pass=encoder.createRenderPass(()->"FarAway Hi-Z occluders",source.getColorTextureView(),freecam?java.util.Optional.of(new Vector4f()):java.util.Optional.empty(),source.getDepthTextureView(),freecam?java.util.OptionalDouble.of(0):java.util.OptionalDouble.empty())) {renderOpaqueContents(pass,selection);}
                finally {occluderPass=false;hiZ.counters.suppressed=false;if(freecam){RenderSystem.restoreProjectionMatrix();anchorProjection.rotate();}}
                encoder.submit();
                if(source.getDepthTexture() instanceof com.mojang.renderpearl.backend.opengl.GlTexture depth)hiZ.begin(depth,depth.getWidth(0),depth.getHeight(0),selection,hiZBounds,gpuMemory);
                skipOccluders=!freecam;visibleCacheValid=false;
            }catch(RuntimeException failure){hiZ.fail(failure);occluderPass=false;skipOccluders=false;visibleCacheValid=false;}
            }
            drawnTiles=0;drawnTriangles=0;renderedSamples=0;
            deferOutlines=hiZ.paintAvailable();
            var encoder=RenderSystem.getDevice().createCommandEncoder();
            try(var pass=encoder.createRenderPass(()->"FarAway Hi-Z final",target.getColorTextureView(),java.util.Optional.empty(),target.getDepthTextureView(),java.util.OptionalDouble.empty())) {renderOpaque(pass,camera);}
            encoder.submit();
            if(hiZ.paintAvailable()) {
                // Rotating a mapped ring inserts an engine fence. It must be outside a render pass.
                if(planeBatches!=null&&planeBatchesUsed){planeBatches.rotate();planeBatchesUsed=false;}
                var atlas=blockAtlas();
                float fogRadius=settings.lodDistanceFog()?radiusChunks*16f:0,clipRadius=settings.clipDistance()?radiusChunks*16f:0;
                hiZ.paintDrawing=true;hiZ.counters.suppressed=true;
                var paintEncoder=RenderSystem.getDevice().createCommandEncoder();
                try(var pass=paintEncoder.createRenderPass(()->"FarAway Hi-Z culled paint",target.getColorTextureView(),java.util.Optional.empty(),target.getDepthTextureView(),java.util.OptionalDouble.empty())) {
                    drawSharedGeometry(pass,camera,atlas,visibleScratch,false,false,camera.fogData.color,fogRadius,-1,clipRadius);
                    drawSharedGeometry(pass,camera,atlas,visibleScratch,true,false,camera.fogData.color,fogRadius,-1,clipRadius);
                    drawPlaneInstances(pass,camera,atlas,visibleScratch,false,camera.fogData.color,fogRadius,-1,clipRadius);
                    // Paint does not write depth: draw outlines last against normal scene depth.
                    deferOutlines=false;renderGenPreview(pass,camera);
                }finally{hiZ.paintDrawing=false;hiZ.counters.suppressed=false;}
                paintEncoder.submit();
            }
            renderFrustumCullingEdges(target,camera);
        } finally {
            hiZ.counters.finish(hiZ.active()?hiZ.visibilityBuffer():null,activeTriangles,frustumTriangles,frameDrawCalls);hiZ.end();deferOutlines=skipOccluders=occluderPass=false;occluders.clear();nearestOccluders.clear();hiZIds.clear();hiZBounds.clear();
            if(enabled) {
                hiZFrames++;hiZCpuNanos+=System.nanoTime()-start;
                if(start>=hiZNextLog){dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.info("FarAway Hi-Z frames={} CPU totalSubmitAvgMs={} resident={} tested={} indirectCommands={} planeCandidates={} occluderLimit=64 freecam={} visibilityReadback=HUD-{}Hz counters={} {}",hiZFrames,hiZCpuNanos/1e6/hiZFrames,tiles.size(),hiZ.testedTiles,hiZ.drawCommands,hiZ.planeCandidates,freecam,settings.hiZDataPollRate(),hiZ.counters.snapshot(),hiZ.profile.summary());hiZFrames=hiZCpuNanos=0;hiZNextLog=start+10_000_000_000L;}
            }
        }
    }
    /** Build outside a render pass, so buffer retirement can safely insert an engine fence. */
    private void renderFrustumCullingEdges(com.mojang.blaze3d.pipeline.RenderTarget target,CameraRenderState camera) {
        var settings=LodSettings.get();
        if(!settings.frustumCulling()||!settings.frustumCullingEdges()||!settings.renderTiles()||preparedEpoch!=epoch){releaseFrustumEdges();return;}
        var eye=dev.faraway.k7q4m9.client.Freecam.anchorOr(camera.pos);
        var clip=new Matrix4f(dev.faraway.k7q4m9.client.Freecam.projectionOr(camera.projectionMatrix)).mul(dev.faraway.k7q4m9.client.Freecam.viewOr(camera.viewRotationMatrix));
        frustumEdgeVisibleScratch.clear();for(var tile:visibleScratch)frustumEdgeVisibleScratch.add(tile.key);
        if(frustumEdgeNeighbours==null||!frustumEdgeVisibleKeys.equals(frustumEdgeVisibleScratch)){
            frustumEdgeVisibleKeys=List.copyOf(frustumEdgeVisibleScratch);frustumEdgeNeighbours=new TileFootprintEdges(frustumEdgeVisibleKeys);
        }
        frustumEdgeSelection.clear();
        for(var tile:visibleScratch){
            int outward=TileVisibility.horizontalBoundaryDirections(tile.bounds,eye.x,eye.y,eye.z,clip);
            if(outward!=0&&frustumEdgeNeighbours.hasExposedEdge(tile.key,outward))frustumEdgeSelection.add(tile);
        }
        if(frustumEdgeSelection.isEmpty()){releaseFrustumEdges();return;}
        var level=Minecraft.getInstance().level;
        int seaLevel=level==null?previewSeaLevel:level.getSeaLevel();
        if(frustumEdgeLines==null || uploadedFrustumEdgeSeaLevel!=seaLevel || !uploadedFrustumEdges.equals(frustumEdgeSelection)) {
            int originX=(int)Math.floor(eye.x),originZ=(int)Math.floor(eye.z);
            var contour=new FrustumOutlineMesh(originX,originZ,seaLevel);
            for(var tile:frustumEdgeSelection) contour.add(tile.key,tile.perimeter,(float)tile.bounds.minY);
            var data=contour.finish();
            var lease=gpuMemory.tryReserve(data.remaining());
            if(lease==null) return;
            GpuBuffer replacement;
            try { replacement=GpuTile.upload(data,GpuBuffer.USAGE_VERTEX); }
            catch(RuntimeException failure) { lease.close();throw failure; }
            releaseFrustumEdgeBuffer();
            frustumEdgeLines=replacement;
            frustumEdgeLease=lease;
            frustumEdgeCount=data.remaining()/16;
            uploadedFrustumEdges=List.copyOf(frustumEdgeSelection);
            uploadedFrustumEdgeSeaLevel=seaLevel;
            frustumEdgeOriginX=originX;frustumEdgeOriginZ=originZ;
        }
        var encoder=RenderSystem.getDevice().createCommandEncoder();
        try(var pass=encoder.createRenderPass(()->"FarAway frustum culling edges",target.getColorTextureView(),java.util.Optional.empty(),target.getDepthTextureView(),java.util.OptionalDouble.empty())){
            pass.setPipeline(RenderSystem.getCompiledPipeline(FRUSTUM_EDGES_OVERLAY));RenderSystem.bindDefaultUniforms(pass);
            var model=new Matrix4f(camera.viewRotationMatrix).translate((float)(frustumEdgeOriginX-camera.pos.x),(float)-camera.pos.y,(float)(frustumEdgeOriginZ-camera.pos.z));
            pass.setUniform("DynamicTransforms",profiledTransform(model,new Vector4f(1,1,1,1),new Vector3f(),new Matrix4f()));
            pass.setVertexBuffer(0,frustumEdgeLines.slice());pass.draw(frustumEdgeCount,1,0,0);
        }
        encoder.submit();
    }
    private void releaseFrustumEdgeBuffer(){
        var buffer=frustumEdgeLines;var lease=frustumEdgeLease;frustumEdgeLines=null;frustumEdgeLease=null;frustumEdgeCount=0;
        if(buffer!=null)RenderSystem.queueFencedTask(()->{try{buffer.close();}finally{if(lease!=null)lease.close();}});
    }
    private void releaseFrustumEdges(){releaseFrustumEdgeBuffer();uploadedFrustumEdges=List.of();frustumEdgeSelection.clear();frustumEdgeVisibleKeys=List.of();frustumEdgeVisibleScratch.clear();frustumEdgeNeighbours=null;}
    private void ensureAnchorDepth(int w,int h) {
        if(anchorDepth!=null&&anchorDepth.getDepthTexture().getWidth(0)==w&&anchorDepth.getDepthTexture().getHeight(0)==h)return;
        releaseAnchorDepth();
        var lease=gpuMemory.tryReserve((long)w*h*8+192);
        if(lease==null)throw new PlaneAtlas.BudgetUnavailable();
        try {
            anchorDepth=new com.mojang.blaze3d.pipeline.TextureTarget("FarAway frozen-camera Hi-Z",w,h,com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM,com.mojang.renderpearl.api.GpuFormat.D32_FLOAT);

            anchorProjection=new net.minecraft.client.renderer.MappableRingBuffer(()->"FarAway Hi-Z projection",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_MAP_WRITE,64);
            anchorDepthLease=lease;
        }catch(RuntimeException e){lease.close();releaseAnchorDepth();throw e;}
    }
    private void releaseAnchorDepth() {
        var target=anchorDepth;var projection=anchorProjection;var lease=anchorDepthLease;
        anchorDepth=null;anchorProjection=null;anchorDepthLease=null;
        if(target!=null||projection!=null)RenderSystem.queueFencedTask(()->{if(target!=null)target.destroyBuffers();if(projection!=null)projection.close();if(lease!=null)lease.close();});
    }
    private void releaseHiZ(){if(hiZResources){hiZ.close();hiZResources=false;}releaseAnchorDepth();}
    public void renderOpaque(RenderPass pass,CameraRenderState camera) {
        if(!LodSettings.get().renderTiles()) {dev.faraway.k7q4m9.client.Freecam.captureView(camera);renderGenPreview(pass,camera);return;}
        gpuProfile.begin(pass);
        try { renderOpaqueContents(pass,camera); }
        finally { gpuProfile.end(pass); }
    }
    private void renderOpaqueContents(RenderPass pass,CameraRenderState camera) {
        dev.faraway.k7q4m9.client.Freecam.captureView(camera);
        if(!LodSettings.get().renderTiles()) { renderGenPreview(pass,camera); return; }
        if(preparedEpoch!=epoch) { return; }
        if(preparedCoverage==null || tiles.isEmpty()) { renderGenPreview(pass,camera); return; }
            long renderStart=System.nanoTime();
            var selectionView=dev.faraway.k7q4m9.client.Freecam.viewOr(camera.viewRotationMatrix);
            var selectionPos=dev.faraway.k7q4m9.client.Freecam.anchorOr(camera.pos);
            var screenMatrix=new Matrix4f(dev.faraway.k7q4m9.client.Freecam.projectionOr(camera.projectionMatrix)).mul(selectionView);
            var atlas=blockAtlas();
            int paletteRevision=dev.faraway.k7q4m9.mesh.TextureMaterial.revision();
            if(materialBuffer==null || materialAtlasView!=atlas.getTextureView() || materialRevision!=paletteRevision) {
                var data=ByteBuffer.allocateDirect(dev.faraway.k7q4m9.mesh.TextureMaterial.MATERIAL_UNIFORM_BYTES).order(java.nio.ByteOrder.nativeOrder());
                for(int i=0;i<dev.faraway.k7q4m9.mesh.TextureMaterial.SPRITES.length;i++) {
                    var sprite=atlas.getSprite(dev.faraway.k7q4m9.mesh.TextureMaterial.sprite(i));
                    data.putFloat(sprite.getU0()).putFloat(sprite.getV0()).putFloat(sprite.getU1()).putFloat(sprite.getV1());
                }
                dev.faraway.k7q4m9.mesh.TextureMaterial.writeTextureVariants(data);
                data.flip();
                var replacement=RenderSystem.getDevice().createBuffer(() -> "FarAway materials",GpuBuffer.USAGE_UNIFORM,data);
                if(materialBuffer!=null) materialBuffer.close();
                materialBuffer=replacement; materialAtlasView=atlas.getTextureView(); materialRevision=paletteRevision;
            }
            var visibleSettings=dev.faraway.k7q4m9.client.LodSettings.get();
            boolean frustumCulling=visibleSettings.frustumCulling();
            long visibilityStart=System.nanoTime();
            int rejectLevel=0,rejectCoverage=0,rejectBehind=0,rejectScreen=0,rejectFrustum=0;
            var visible=visibleScratch;
            boolean cached=!occluderPass && !skipOccluders && TileKey.WORLD && !dev.faraway.k7q4m9.client.Freecam.active() && visibleCacheValid
                && visibleCacheX==selectionPos.x && visibleCacheY==selectionPos.y && visibleCacheZ==selectionPos.z
                && visibleCacheMinLod==visibleSettings.minLodVisible() && visibleCacheMaxLod==visibleSettings.maxLodVisible()
                && visibleCacheFrustumCulling==frustumCulling
                && visibleCacheMatrix.equals(screenMatrix);
            if(cached) { drawnTiles=visible.size(); drawnTriangles=visibleCacheTriangles; renderedSamples=visibleCacheSamples;activeTriangles=cachedActiveTriangles;frustumTriangles=cachedFrustumTriangles; }
            else {
            if(!occluderPass){activeTriangles=frustumTriangles=0;}
            visible.clear();
            for(GpuTile tile:tiles) {
                if(occluderPass&&!occluders.contains(tile))continue;
                int level=Integer.numberOfTrailingZeros(tile.key.spacing());
                if(level<visibleSettings.minLodVisible() || level>visibleSettings.maxLodVisible()) { rejectLevel++; continue; }
                if(streaming!=null && !streaming.drawable(tile.key)) { rejectCoverage++; continue; }
                if(!occluderPass)activeTriangles+=tile.triangles();
                if(frustumCulling) {
                    if(TileVisibility.entirelyBehind(tile.bounds,selectionPos.x,selectionPos.y,selectionPos.z,selectionView)) { rejectBehind++; continue; }
                    if(!TileVisibility.intersectsScreen(tile.bounds,selectionPos.x,selectionPos.y,selectionPos.z,screenMatrix)) { rejectScreen++; continue; }
                    if(!TileKey.WORLD && !dev.faraway.k7q4m9.client.Freecam.debugCamActive() && !camera.cullFrustum.isVisible(tile.bounds)) { rejectFrustum++; continue; }
                }
                if(!occluderPass)frustumTriangles+=tile.triangles();
                if(tile.triangles()==0) continue;
                if(tile.plane!=null && selectionPos.y<tile.bounds.minY) continue;
                visible.add(tile); drawnTiles++; drawnTriangles+=tile.triangles(); renderedSamples+=horizontalSamples(tile.key);
            }
            visibleCacheValid=!occluderPass && !skipOccluders && TileKey.WORLD && !dev.faraway.k7q4m9.client.Freecam.active();
            visibleCacheX=selectionPos.x; visibleCacheY=selectionPos.y; visibleCacheZ=selectionPos.z;
            visibleCacheMinLod=visibleSettings.minLodVisible(); visibleCacheMaxLod=visibleSettings.maxLodVisible();
            visibleCacheFrustumCulling=frustumCulling;
            if(!occluderPass){cachedActiveTriangles=activeTriangles;cachedFrustumTriangles=frustumTriangles;}
            visibleCacheMatrix.set(screenMatrix); visibleCacheTriangles=drawnTriangles; visibleCacheSamples=renderedSamples;
            if(!occluderPass)RenderProfile.INSTANCE.visibility(tiles.size(),rejectLevel,rejectCoverage,rejectBehind,rejectScreen,rejectFrustum,visible.size());
            }
            RenderProfile.INSTANCE.add(VISIBILITY,System.nanoTime()-visibilityStart);
            boolean wireframe=visibleSettings.wireframe();
            float fogRadius=visibleSettings.lodDistanceFog()?radiusChunks*16f:0;
            float colorMode=visibleSettings.colorByLod()?1:0;
            float clipRadius=visibleSettings.clipDistance()?radiusChunks*16f:0;
            var fogColor=camera.fogData.color;
            int terrainCalls=0,waterCalls=0,pipelineChanges=0;
            long terrainStart=System.nanoTime();
            if(hiZ.active())prepareHiZGeometry(visible,camera);
            gpuProfile.startStage(pass,0);
            terrainCalls=drawSharedGeometry(pass,camera,atlas,visible,false,wireframe,fogColor,fogRadius,colorMode,clipRadius);
            if(!occluderPass && !wireframe && vanillaSeam!=null && preparedCoverage!=emptyCoverageBuffer) {
                terrainCalls+=drawVanillaSeam(pass,camera,atlas,fogRadius,clipRadius,visibleSettings);
            }
            if(terrainCalls>0)pipelineChanges++;
            gpuProfile.endStage(pass,0);
            if(occluderPass){frameDrawCalls+=terrainCalls;return;}
            long planeStart=System.nanoTime();
            var planeStats=drawPlaneInstances(pass,camera,atlas,visible,wireframe,fogColor,fogRadius,colorMode,clipRadius);
            if(!planeStats.instanced()) gpuProfile.startStage(pass,1);
            if(!planeStats.instanced()) for(var tile:visible) if(tile.plane!=null) {
                var slot=tile.plane;
                pass.setPipeline(RenderSystem.getCompiledPipeline(wireframe?PLANE_WIREFRAME:PLANE)); RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("FarAwayCoverage",preparedCoverage.buffer.slice());
                pass.setUniform("Sampler0",slot.page.view,atlas.getSampler());
                pass.setUniform("Sampler2",Minecraft.getInstance().gameRenderer.levelLightmap(),atlas.getSampler());
                var model=new Matrix4f(camera.viewRotationMatrix).translate((float)(tile.key.originX()-camera.pos.x),(float)(tile.bounds.minY-camera.pos.y),(float)(tile.key.originZ()-camera.pos.z)).scale(tile.key.blocks(),1,tile.key.blocks());
                var textureMatrix=new Matrix4f().setColumn(0,new Vector4f(slot.x(),slot.y(),slot.side,0)).setColumn(1,fogColor)
                    .setColumn(2,new Vector4f(fogRadius,colorMode,tile.key.blocks(),tile.key.spacing()))
                    .setColumn(3,new Vector4f((float)(camera.pos.x-tile.key.originX()),(float)(camera.pos.z-tile.key.originZ()),clipRadius,1));
                pass.setUniform("DynamicTransforms",profiledTransform(model,new Vector4f(1,1,1,1),new Vector3f(tile.key.originX()/16f,LodColors.forSpacing(tile.key.spacing()),tile.key.originZ()/16f),textureMatrix));
                hiZ.counters.add(hiZIds.getOrDefault(tile,-1),2,false);
                pass.setVertexBuffer(0,planeVertices.slice()); if(LodSettings.get().drawCalls())pass.draw(6,1,0,0);
            }
            gpuProfile.endStage(pass,1);
            long waterStart=System.nanoTime();
            long planeElapsed=waterStart-planeStart;
            RenderProfile.INSTANCE.add(PLANE_BUILD,planeStats.buildNanos());
            RenderProfile.INSTANCE.add(PLANE_SUBMIT,planeStats.instanced()?planeStats.drawNanos():planeElapsed-planeStats.buildNanos());
            profilePlaneBuildNs+=planeStats.buildNanos(); profilePlaneDrawNs+=planeStats.instanced()?planeStats.drawNanos():planeElapsed-planeStats.buildNanos();
            profilePlaneMaxNs=Math.max(profilePlaneMaxNs,planeElapsed); profilePlaneTiles+=planeStats.tiles(); profilePlanePages+=planeStats.pages();
            profilePlaneCalls+=planeStats.instanced()?planeStats.pages():planeStats.tiles(); profilePlaneFallback+=planeStats.instanced()?0:planeStats.tiles(); profilePlaneBytes+=planeStats.bytes();
            long terrainElapsed=planeStart-terrainStart;
            RenderProfile.INSTANCE.add(TERRAIN_SUBMIT,terrainElapsed);
            profileTerrainNs+=terrainElapsed; profileTerrainMaxNs=Math.max(profileTerrainMaxNs,terrainElapsed);
            gpuProfile.startStage(pass,2);
            waterCalls=drawSharedGeometry(pass,camera,atlas,visible,true,wireframe,fogColor,fogRadius,colorMode,clipRadius);
            if(waterCalls>0)pipelineChanges++;
            backfaceTriangles=!wireframe && vanillaSeam!=null && preparedCoverage!=emptyCoverageBuffer?vanillaSeam.vertices()/3:0;
            prePlaneTiles=preVoxelTiles=0;
            for(var tile:visible) {
                long triangles=tile.plane!=null?2:(tile.sharedTerrain==null?0:tile.sharedTerrain.selectedTriangles)+(tile.sharedQuads==null?0:tile.sharedQuads.selectedTriangles)+(tile.sharedWater==null?0:tile.sharedWater.selectedTriangles);
                backfaceTriangles+=triangles;
                if(visibleSettings.drawCalls() && triangles>0){if(tile.plane!=null)prePlaneTiles++;else preVoxelTiles++;}
            }

            gpuProfile.endStage(pass,2);
            long waterElapsed=System.nanoTime()-waterStart;
            RenderProfile.INSTANCE.add(WATER_SUBMIT,waterElapsed);
            if(visibleSettings.drawCalls())frameDrawCalls+=terrainCalls+waterCalls+(planeStats.instanced()?planeStats.pages():planeStats.tiles());
            RenderProfile.INSTANCE.draws(terrainCalls+waterCalls+(planeStats.instanced()?planeStats.pages():planeStats.tiles()),pipelineChanges+(planeStats.tiles()>0?(planeStats.instanced()?1:planeStats.tiles()):0));
            profileWaterNs+=waterElapsed; profileWaterMaxNs=Math.max(profileWaterMaxNs,waterElapsed);
            renderGenPreview(pass,camera);
            long elapsed=System.nanoTime()-renderStart; profileRenderNs+=elapsed; profileRenderMaxNs=Math.max(profileRenderMaxNs,elapsed);
            profileFrames++;
            if(renderProfileStart==0) renderProfileStart=renderStart;
            if(renderStart-renderProfileStart>=10_000_000_000L) {
                dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.info("FarAway render CPU profile frames={} coverageAvgMs={} coverageMaxMs={} uploadAvgMs={} uploadMaxMs={} submitAvgMs={} submitMaxMs={} terrainAvgMs={} terrainMaxMs={} waterAvgMs={} waterMaxMs={} resident={} drawn={} drawnTriangles={} terrainCalls={} waterCalls={} pipelineChanges={} genPreview={}",
                    profileFrames,profileCoverageNs/1_000_000.0/Math.max(1,profilePrepareFrames),profileCoverageMaxNs/1_000_000.0,profileUploadNs/1_000_000.0/Math.max(1,profilePrepareFrames),profileUploadMaxNs/1_000_000.0,
                    profileRenderNs/1_000_000.0/profileFrames,profileRenderMaxNs/1_000_000.0,profileTerrainNs/1_000_000.0/profileFrames,profileTerrainMaxNs/1_000_000.0,
                    profileWaterNs/1_000_000.0/profileFrames,profileWaterMaxNs/1_000_000.0,tiles.size(),drawnTiles,drawnTriangles,terrainCalls,waterCalls,pipelineChanges,visibleSettings.genPreview());
                dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.info("FarAway plane CPU profile frames={} tilesAvg={} atlasPagesAvg={} drawCallsAvg={} fallbackTilesAvg={} instanceBytesAvg={} buildAvgMs={} drawAvgMs={} totalMaxMs={} instancingUnavailable={} batchCapacity={} batchReservedBytes={}",
                    profileFrames,(double)profilePlaneTiles/profileFrames,(double)profilePlanePages/profileFrames,(double)profilePlaneCalls/profileFrames,(double)profilePlaneFallback/profileFrames,(double)profilePlaneBytes/profileFrames,profilePlaneBuildNs/1_000_000.0/profileFrames,profilePlaneDrawNs/1_000_000.0/profileFrames,profilePlaneMaxNs/1_000_000.0,planeInstancingUnavailable,planeBatchCapacity,planeBatchReservation==null?0:planeBatchReservation.bytes());
                renderProfileStart=renderStart; profileFrames=profilePrepareFrames=profileCoverageNs=profileCoverageMaxNs=profileRenderNs=profileRenderMaxNs=profileUploadNs=profileUploadMaxNs=0;
                profileTerrainNs=profileTerrainMaxNs=profileWaterNs=profileWaterMaxNs=0;
                profilePlaneBuildNs=profilePlaneDrawNs=profilePlaneMaxNs=profilePlaneTiles=profilePlanePages=profilePlaneCalls=profilePlaneFallback=profilePlaneBytes=0;
            }
    }
    private record PlaneDrawStats(boolean instanced,int tiles,int pages,long bytes,long buildNanos,long drawNanos) {}
    private static final class PlaneGroup {
        final PlaneAtlas.Page page;
        final ArrayList<GpuTile> tiles=new ArrayList<>(64);
        long lastUsed;
        PlaneGroup(PlaneAtlas.Page page) { this.page=page; }
    }
    private void retirePlaneBuffer() {
        var old=planeBatches; var lease=planeBatchReservation;
        planeBatches=null; planeBatchReservation=null; planeBatchesUsed=false;
        planeBatchCapacity=0; planeUnderusedSince=0;
        if(old!=null) {
            long bytes=lease==null?0:lease.bytes(); retiringBytes.addAndGet(bytes);
            deferredPlaneRetirements.addLast(() -> {
                try { old.close(); } finally { if(lease!=null) lease.close(); retiringBytes.addAndGet(-bytes); }
            });
        } else if(lease!=null) lease.close();
    }
    private void submitPlaneRetirements() {
        while(!deferredPlaneRetirements.isEmpty()) {
            RenderSystem.queueFencedTask(deferredPlaneRetirements.peekFirst());
            deferredPlaneRetirements.removeFirst();
        }
    }
    private boolean ensurePlaneCapacity(int pages,long now) {
        int capacity=1;
        while(capacity<pages) capacity=Math.multiplyExact(capacity,2);
        boolean shrink=planeBatches!=null && capacity<=planeBatchCapacity/4;
        if(shrink) {
            if(planeUnderusedSince==0) planeUnderusedSince=now;
            if(now-planeUnderusedSince<5_000_000_000L) return true;
        } else planeUnderusedSince=0;
        if(planeBatches!=null && capacity<=planeBatchCapacity && !shrink) return true;
        int alignment=RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        int stride=((64*48+alignment-1)/alignment)*alignment;
        int bytes=Math.multiplyExact(capacity,stride);
        var lease=gpuMemory.tryReserve((long)bytes*3);
        if(lease==null) { if(shrink) retirePlaneBuffer(); return false; }
        net.minecraft.client.renderer.MappableRingBuffer replacement;
        try {
            replacement=new net.minecraft.client.renderer.MappableRingBuffer(() -> "FarAway plane instances",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_MAP_WRITE,bytes);
        } catch(RuntimeException failure) { lease.close(); throw failure; }
        retirePlaneBuffer(); planeBatches=replacement; planeBatchReservation=lease;
        planeBatchCapacity=capacity; planeBatchStride=stride;
        return true;
    }
    private PlaneDrawStats drawPlaneInstances(RenderPass pass,CameraRenderState camera,net.minecraft.client.renderer.texture.TextureAtlas atlas,
            List<GpuTile> visible,boolean wireframe,org.joml.Vector4fc fogColor,float fogRadius,float colorMode,float clipRadius) {
        long start=System.nanoTime();
        for(var group:activePlaneGroups) group.tiles.clear();
        activePlaneGroups.clear();
        int count=0;
        for(var tile:visible) if(tile.plane!=null) {
            var group=planeGroups.get(tile.plane.page);
            if(group==null) { group=new PlaneGroup(tile.plane.page); planeGroups.put(tile.plane.page,group); }
            if(group.tiles.isEmpty()) activePlaneGroups.add(group);
            group.tiles.add(tile); group.lastUsed=start; count++;
        }
        planeGroups.values().removeIf(group -> start-group.lastUsed>=5_000_000_000L);
        int pages=activePlaneGroups.size();
        if(count==0) return new PlaneDrawStats(true,0,0,0,System.nanoTime()-start,0);
        planeLastUsed=start;
        if(start<planePipelineRetryAt || start<planeBufferRetryAt) return new PlaneDrawStats(false,count,pages,0,System.nanoTime()-start,0);
        com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline compiled;
        try {
            compiled=RenderSystem.getCompiledPipeline(hiZ.paintDrawing?PAINT_PLANE:wireframe?PLANE_INSTANCED_WIREFRAME:PLANE_INSTANCED);
            planeInstancingUnavailable=false;
        } catch(RuntimeException failure) {
            planeInstancingUnavailable=true; planePipelineRetryAt=start+30_000_000_000L;
            dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.warn("Plane instance pipeline unavailable; retrying in 30 seconds",failure);
            return new PlaneDrawStats(false,count,pages,0,System.nanoTime()-start,0);
        }
        com.mojang.renderpearl.api.buffers.GpuBuffer buffer;
        try {
            if(!ensurePlaneCapacity(pages,start)) return new PlaneDrawStats(false,count,pages,0,System.nanoTime()-start,0);
            long fenceStart=System.nanoTime();
            buffer=planeBatches.currentBuffer();
            RenderProfile.INSTANCE.add(INSTANCE_FENCE,System.nanoTime()-fenceStart);
            planeBatchesUsed=true;
            long mapStart=System.nanoTime();
            try(var mapped=buffer.slice(0,(long)pages*planeBatchStride).map(false,true)) {
                RenderProfile.INSTANCE.add(INSTANCE_MAP,System.nanoTime()-mapStart);
                long writeStart=System.nanoTime();
                var data=mapped.data().order(java.nio.ByteOrder.nativeOrder());
                for(int g=0;g<pages;g++) {
                    var group=activePlaneGroups.get(g);
                    if(group.tiles.size()>64) throw new IllegalStateException("Plane atlas page exceeds 64 instances");
                    data.position(g*planeBatchStride);
                    for(var tile:group.tiles) {
                        var slot=tile.plane;
                        data.putFloat((float)(tile.key.originX()-camera.pos.x)).putFloat((float)(tile.bounds.minY-camera.pos.y)).putFloat((float)(tile.key.originZ()-camera.pos.z)).putFloat(tile.key.blocks());
                        data.putFloat(slot.x()).putFloat(slot.y()).putFloat(slot.side).putFloat(tile.key.spacing());
                        data.putFloat(tile.key.originX()/16f).putFloat(LodColors.forSpacing(tile.key.spacing())).putFloat(tile.key.originZ()/16f).putFloat(hiZIds.getOrDefault(tile,-1));
                    }
                }
                RenderProfile.INSTANCE.add(INSTANCE_WRITE,System.nanoTime()-writeStart);
                mapStart=System.nanoTime(); // Includes flush/unmap cost after close.
            }
            RenderProfile.INSTANCE.add(INSTANCE_MAP,System.nanoTime()-mapStart);
        } catch(RuntimeException failure) {
            retirePlaneBuffer(); planeBufferRetryAt=start+1_000_000_000L;
            dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.warn("Plane instance buffer unavailable; retrying in one second",failure);
            return new PlaneDrawStats(false,count,pages,0,System.nanoTime()-start,0);
        }
        long drawStart=System.nanoTime();
        gpuProfile.startStage(pass,1);
        var textureMatrix=new Matrix4f().setColumn(1,fogColor).setColumn(2,new Vector4f(fogRadius,colorMode,0,0)).setColumn(3,new Vector4f(0,0,clipRadius,1));
        var transform=profiledTransform(new Matrix4f(camera.viewRotationMatrix),new Vector4f(1,1,1,1),new Vector3f(),textureMatrix);
        pass.setPipeline(compiled); RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms",transform); pass.setUniform("FarAwayCoverage",preparedCoverage.buffer.slice());
        pass.setUniform("Sampler2",Minecraft.getInstance().gameRenderer.levelLightmap(),atlas.getSampler());
        pass.setVertexBuffer(0,planeVertices.slice());
        HiZ.PlaneDraw[] filteredGroups=null;
        if(hiZ.active()){int[] counts=new int[pages];for(int g=0;g<pages;g++)counts[g]=activePlaneGroups.get(g).tiles.size();filteredGroups=hiZ.compactAll(buffer.slice(),planeBatchStride,counts);}
        for(int g=0;g<pages;g++) {
            var group=activePlaneGroups.get(g);
            pass.setUniform("Sampler0",group.page.view,atlas.getSampler());
            var input=buffer.slice((long)g*planeBatchStride,64*48);
            var filtered=filteredGroups==null?null:filteredGroups[g];
            if(hiZ.paintDrawing&&filtered==null)continue;
            if(hiZ.counters.collecting())for(var tile:group.tiles)hiZ.counters.add(hiZIds.getOrDefault(tile,-1),2,filtered!=null);
            pass.setUniform("FarAwayPlaneInstances",filtered==null?input:filtered.instances());
            if(LodSettings.get().drawCalls()){if(filtered==null)pass.draw(6,group.tiles.size(),0,0);else pass.drawIndirect(filtered.command(),1);}
        }
        return new PlaneDrawStats(true,count,pages,(long)count*48,drawStart-start,System.nanoTime()-drawStart);
    }
    private void prepareHiZGeometry(List<GpuTile> visible,CameraRenderState camera) {
        for(var page:geometryPages)page.visible.clear();geometryPages.clear();
        for(var tile:visible)for(boolean water:new boolean[]{false,true}) {
            if(!water&&skipOccluders&&occluders.contains(tile))continue;
            var slot=water?tile.sharedWater:tile.sharedTerrain;
            if(slot!=null){slot.hiZId=hiZIds.getOrDefault(tile,-1);slot.page.visible.add(slot);geometryPages.add(slot.page);}
            if(!water && tile.sharedQuads!=null){var quad=tile.sharedQuads;quad.hiZId=hiZIds.getOrDefault(tile,-1);quad.page.visible.add(quad);geometryPages.add(quad.page);}
        }
        var pos=dev.faraway.k7q4m9.client.Freecam.anchorOr(camera.pos);
        for(var page:geometryPages)page.prepareHiZ(pos.x,pos.y,pos.z,hiZ);
        hiZ.filterCommands();
    }
    private int drawSharedGeometry(RenderPass pass,CameraRenderState camera,net.minecraft.client.renderer.texture.TextureAtlas atlas,List<GpuTile> visible,boolean water,boolean wire,org.joml.Vector4fc fog,float radius,float colors,float clip) {
        for(var page:geometryPages)page.visible.clear();geometryPages.clear();
        for(var tile:visible) {if(!water&&skipOccluders&&occluders.contains(tile))continue;var slot=water?tile.sharedWater:tile.sharedTerrain;if(slot!=null){slot.hiZId=hiZIds.getOrDefault(tile,-1);slot.page.visible.add(slot);geometryPages.add(slot.page);}if(!water&&tile.sharedQuads!=null){var quad=tile.sharedQuads;quad.hiZId=hiZIds.getOrDefault(tile,-1);quad.page.visible.add(quad);geometryPages.add(quad.page);}}
        if(geometryPages.isEmpty())return 0;
        var drawSettings=LodSettings.get();
        boolean cull=drawSettings.gpuBackfaceCulling();
        var selectedPipeline=hiZ.paintDrawing?(water?(cull?PAINT_WATER:PAINT_WATER_NO_CULL):(cull?PAINT_TERRAIN:PAINT_TERRAIN_NO_CULL))
            :cull?(water&&!wire?WATER:wire?WIREFRAME:PIPELINE):(water&&!wire?WATER_NO_CULL:wire?WIREFRAME_NO_CULL:TERRAIN_NO_CULL);
        var sprite=atlas.getSprite(Identifier.withDefaultNamespace("block/water_still"));int calls=0;
        var model=new Matrix4f();var texture=new Matrix4f();var offset=new Vector3f();var params=new Vector4f();var white=new Vector4f(1,1,1,1);
        var blockSampler=blockTextureFiltering.sampler(atlas);
        RenderPipeline bound=null;
        for(var page:geometryPages) {
            var pipeline=page.key.packed()?(hiZ.paintDrawing?(cull?QUADS_PAINT:QUADS_PAINT_NO_CULL):wire?(cull?QUADS_WIRE:QUADS_WIRE_NO_CULL):(cull?QUADS:QUADS_NO_CULL)):selectedPipeline;
            if(bound!=pipeline) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("FarAwayCoverage",preparedCoverage.buffer.slice());if(!water||wire)pass.setUniform("FarAwayMaterials",materialBuffer.slice());
                pass.setUniform("Sampler0",atlas.getTextureView(),blockSampler);pass.setUniform("Sampler2",Minecraft.getInstance().gameRenderer.levelLightmap(),atlas.getSampler());
                bound=pipeline;
            }
            var key=page.key;
            model.set(camera.viewRotationMatrix).translate((float)(key.x()-camera.pos.x),(float)-camera.pos.y,(float)(key.z()-camera.pos.z));
            texture.identity().setColumn(1,fog).setColumn(2,params.set(radius,colors,key.spacing(),LodSettings.get().opaqueLeaves()?1:0)).setColumn(3,params.set((float)(camera.pos.x-key.x()),(float)(camera.pos.z-key.z()),clip,1));
            if(water&&!wire)texture.setColumn(0,params.set(sprite.getU0(),sprite.getV0(),sprite.getU1(),sprite.getV1()));
            else texture.setColumn(0,params.set(drawSettings.randomizedTextures()?1:0,0,0,0));
            pass.setUniform("DynamicTransforms",profiledTransform(model,white,offset.set(key.x()/16f,LodColors.forSpacing(key.spacing()),key.z()/16f),texture));
            var selection=dev.faraway.k7q4m9.client.Freecam.anchorOr(camera.pos);
            calls+=page.draw(pass,selection.x,selection.y,selection.z,hiZ);
        }
        return calls;
    }
    private static com.mojang.renderpearl.api.buffers.GpuBufferSlice profiledTransform(Matrix4f model,Vector4f color,Vector3f offset,Matrix4f texture) {
        long start=System.nanoTime();
        try { return RenderSystem.getDynamicUniforms().writeTransform(UniformTransforms.snapshot(model,color,offset,texture)); }
        finally { RenderProfile.INSTANCE.add(DYNAMIC_UNIFORMS,System.nanoTime()-start); }
    }
    private void renderGenPreview(RenderPass pass,CameraRenderState camera) {
        if(deferOutlines)return;
        long start=System.nanoTime();
        gpuProfile.startStage(pass,3);
        try { renderGenPreviewContents(pass,camera); }
        finally { gpuProfile.endStage(pass,3); RenderProfile.INSTANCE.add(DEBUG_SUBMIT,System.nanoTime()-start); }
    }
    private void preparePreviewLines() {
        var settings=LodSettings.get();
        if(!settings.genPreview())return;
        var squares=previewSquares;var edges=previewEdges;int mode=settings.previewMode();
        if(previewLines!=null && uploadedPreviewMode==mode && uploadedPreviewMin==settings.minLodVisible() && uploadedPreviewMax==settings.maxLodVisible()
            && uploadedPlannedLods==settings.showPlannedLods() && uploadedPreviewSquares==squares && uploadedPreviewEdges==edges)return;
        int count=(settings.showPlannedLods()?squares.size()*8:0)+edges.size()*2;
        if(count==0){previewLineCount=0;return;}
        int bytes=Math.multiplyExact(count,16);var lease=gpuMemory.tryReserve(bytes);if(lease==null)return;
        double anchorX=previewX,anchorZ=previewZ;
        var data=MemoryUtil.memAlloc(bytes).order(java.nio.ByteOrder.nativeOrder());
        GpuBuffer replacement;
        try {
            if(settings.showPlannedLods())for(var square:squares) {
                if(square.lod()<settings.minLodVisible() || square.lod()>settings.maxLodVisible())continue;
                var key=square.region();double inset=key.blocks()*.02;
                double x=key.originX()+inset-anchorX,z=key.originZ()+inset-anchorZ,size=key.blocks()*.96;int color=LodColors.forSpacing(1<<square.lod());
                previewPoint(data,x,previewSeaLevel,z,color);previewPoint(data,x+size,previewSeaLevel,z,color);
                previewPoint(data,x+size,previewSeaLevel,z,color);previewPoint(data,x+size,previewSeaLevel,z+size,color);
                previewPoint(data,x+size,previewSeaLevel,z+size,color);previewPoint(data,x,previewSeaLevel,z+size,color);
                previewPoint(data,x,previewSeaLevel,z+size,color);previewPoint(data,x,previewSeaLevel,z,color);
            }
            if(mode>=2)for(var edge:edges) {
                if(mode==2 && !edge.outer())continue;
                int y=edge.outer()?0:previewSeaLevel,color=edge.outer()?0xff0000:0xff008c;
                previewPoint(data,edge.x1()-anchorX,y,edge.z1()-anchorZ,color);previewPoint(data,edge.x2()-anchorX,y,edge.z2()-anchorZ,color);
            }
            data.flip();count=data.remaining()/16;
            if(count==0){lease.close();previewLineCount=0;return;}
            replacement=RenderSystem.getDevice().createBuffer(()->"FarAway batched preview",GpuBuffer.USAGE_VERTEX,data);
        } catch(RuntimeException e){lease.close();throw e;} finally{MemoryUtil.memFree(data);}
        var old=previewLines;var oldLease=previewLineLease;
        if(old!=null)RenderSystem.queueFencedTask(()->{try{old.close();}finally{if(oldLease!=null)oldLease.close();}});
        previewLines=replacement;previewLineLease=lease;previewLineCount=count;previewAnchorX=anchorX;previewAnchorZ=anchorZ;
        uploadedPreviewSquares=squares;uploadedPreviewEdges=edges;uploadedPreviewMode=mode;uploadedPlannedLods=settings.showPlannedLods();uploadedPreviewMin=settings.minLodVisible();uploadedPreviewMax=settings.maxLodVisible();
    }
    private static void previewPoint(ByteBuffer data,double x,double y,double z,int color) {
        data.putFloat((float)x).putFloat((float)y).putFloat((float)z).put((byte)(color>>16)).put((byte)(color>>8)).put((byte)color).put((byte)255);
    }
    private void renderGenPreviewContents(RenderPass pass,CameraRenderState camera) {
        drawFreecamFrustum(pass,camera,false);
    }
    /** Executed after the complete world render, including transparency and Hi-Z paint. */
    public void renderPreviewOverlay(com.mojang.blaze3d.pipeline.RenderTarget target,CameraRenderState camera) {
        long callTime=System.nanoTime();drawCallWindow.add(callTime,frameDrawCalls);drawCallWindow10.add(callTime,frameDrawCalls);
        boolean plannedOutlines=LodSettings.get().genPreview() && previewLines!=null && previewLineCount>0;
        boolean debugFrustum=dev.faraway.k7q4m9.client.Freecam.frustum()!=null;
        if(!plannedOutlines && !debugFrustum)return;
        var encoder=RenderSystem.getDevice().createCommandEncoder();
        try(var pass=encoder.createRenderPass(()->"FarAway outline overlay",target.getColorTextureView(),java.util.Optional.empty(),target.getDepthTextureView(),java.util.OptionalDouble.empty())) {
            if(plannedOutlines) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(FRUSTUM_EDGES_OVERLAY));RenderSystem.bindDefaultUniforms(pass);
                var transform=new Matrix4f(camera.viewRotationMatrix).translate((float)(previewAnchorX-camera.pos.x),(float)-camera.pos.y,(float)(previewAnchorZ-camera.pos.z));
                pass.setUniform("DynamicTransforms",profiledTransform(transform,new Vector4f(1,1,1,1),new Vector3f(),new Matrix4f()));
                pass.setVertexBuffer(0,previewLines.slice());pass.draw(previewLineCount,1,0,0);
            }
            if(debugFrustum) drawFreecamFrustum(pass,camera,true);
        }
        encoder.submit();
    }
    private void drawFreecamFrustum(RenderPass pass,CameraRenderState camera,boolean overlay) {
        var shape=dev.faraway.k7q4m9.client.Freecam.frustum();
        if(shape==null) return;
        if(uploadedFrustum!=shape) {
            if(frustumLines!=null) frustumLines.close(); if(frustumArrow!=null) frustumArrow.close();
            var geometry=dev.faraway.k7q4m9.client.Freecam.frustumGeometry();
            var linePoints=geometry.wire();
            frustumLineVertices=linePoints.size();
            frustumFrameVertices=geometry.frameVertices();
            var lines=ByteBuffer.allocateDirect(frustumLineVertices*16).order(java.nio.ByteOrder.nativeOrder());
            for(var point:linePoints) frustumVertex(lines,point);
            lines.flip(); frustumLines=RenderSystem.getDevice().createBuffer(() -> "FarAway frustum lines",GpuBuffer.USAGE_VERTEX,lines);
            var arrow=ByteBuffer.allocateDirect(3*16).order(java.nio.ByteOrder.nativeOrder());
            for(var point:shape.arrow()) frustumVertex(arrow,point);
            arrow.flip(); frustumArrow=RenderSystem.getDevice().createBuffer(() -> "FarAway frustum arrow",GpuBuffer.USAGE_VERTEX,arrow);
            uploadedFrustum=shape;
        }
        int first=overlay?0:frustumFrameVertices;
        int count=overlay?frustumFrameVertices:frustumLineVertices-frustumFrameVertices;
        if(count==0) return;
        var origin=shape.origin();
        var transform=new Matrix4f(camera.viewRotationMatrix).translate((float)(origin.x-camera.pos.x),(float)(origin.y-camera.pos.y),(float)(origin.z-camera.pos.z));
        var uniform=profiledTransform(transform,new Vector4f(1,1,0,1),new Vector3f(),new Matrix4f());
        pass.setPipeline(RenderSystem.getCompiledPipeline(overlay?FRUSTUM_EDGES_OVERLAY:GEN_PREVIEW)); RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms",uniform); pass.setVertexBuffer(0,frustumLines.slice()); pass.draw(count,1,first,0);
        if(overlay) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(FRUSTUM_ARROW)); RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms",uniform); pass.setVertexBuffer(0,frustumArrow.slice()); pass.draw(3,1,0,0);
        }
    }
    private static void frustumVertex(ByteBuffer buffer,net.minecraft.world.phys.Vec3 point) {
        buffer.putFloat((float)point.x).putFloat((float)point.y).putFloat((float)point.z).putInt(-1);
    }
    private void prepareVanillaSeam(LodSettings.Values settings) {
        if(tiles.isEmpty() || settings.wireframe() || vanillaCoverage.readyColumns()==0) {
            releaseVanillaSeam();
            return;
        }

        var edges=vanillaCoverage.edges();
        var drawn=streaming!=null && TileKey.WORLD?streaming.displayedTiles():null;
        if(!seamDirty && seamDrawn==drawn && seamColored==settings.colorByLod()
            && seamMinLod==settings.minLodVisible() && seamMaxLod==settings.maxLodVisible()
            && seamEdges.equals(edges)) return;

        var geometry=new VanillaSeamMesh(edges);
        var sources=new ArrayList<GpuTile>();
        for(var tile:tiles) {
            if(tile.voxelSurface==null || !geometry.intersects(tile.key) || drawn!=null && !drawn.contains(tile.key)) continue;
            int lod=Integer.numberOfTrailingZeros(tile.key.spacing());
            if(lod>=settings.minLodVisible() && lod<=settings.maxLodVisible()) sources.add(tile);
        }
        if(seamColored==settings.colorByLod() && seamEdges.equals(edges) && seamSources.equals(sources)) {
            rememberSeamPlan(settings,edges,sources,drawn);
            return;
        }
        for(var tile:sources) geometry.addTile(tile.key,tile.voxelSurface,settings.colorByLod());
        var data=geometry.data();
        releaseVanillaSeam();
        if(data.hasRemaining()) {
            var lease=gpuMemory.tryReserve(data.remaining());
            if(lease==null) return;
            try {
                vanillaSeam=new VanillaSeam(GpuTile.upload(data,GpuBuffer.USAGE_VERTEX),lease,
                    geometry.originX(),geometry.originZ(),data.remaining()/16);
            } catch(RuntimeException failure) {
                lease.close();
                throw failure;
            }
        }
        patchedSeamChunks=geometry.patchedChunks();
        rememberSeamPlan(settings,edges,sources,drawn);
    }

    private void rememberSeamPlan(LodSettings.Values settings,List<VanillaCoverage.Edge> edges,List<GpuTile> sources,
                                  java.util.Set<TileKey> drawn) {
        seamEdges=edges;
        seamSources=sources;
        seamDrawn=drawn;
        seamColored=settings.colorByLod();
        seamMinLod=settings.minLodVisible();
        seamMaxLod=settings.maxLodVisible();
        seamDirty=false;
    }

    private int drawVanillaSeam(RenderPass pass,CameraRenderState camera,net.minecraft.client.renderer.texture.TextureAtlas atlas,
                                float fogRadius,float clipRadius,LodSettings.Values settings) {
        var seam=vanillaSeam;
        int x=seam.originX(),z=seam.originZ();
        var model=new Matrix4f(camera.viewRotationMatrix).translate((float)(x-camera.pos.x),(float)-camera.pos.y,(float)(z-camera.pos.z));
        var texture=new Matrix4f().setColumn(0,new Vector4f(settings.randomizedTextures()?1:0,1,0,0))
            .setColumn(1,camera.fogData.color).setColumn(2,new Vector4f(fogRadius,settings.colorByLod()?2:0,1,0))
            .setColumn(3,new Vector4f((float)(camera.pos.x-x),(float)(camera.pos.z-z),clipRadius,1));
        pass.setPipeline(RenderSystem.getCompiledPipeline(settings.gpuBackfaceCulling()?VANILLA_SEAM_CULL:VANILLA_SEAM));
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("FarAwayCoverage",preparedCoverage.buffer.slice());
        pass.setUniform("FarAwayMaterials",materialBuffer.slice());
        pass.setUniform("Sampler0",atlas.getTextureView(),blockTextureFiltering.sampler(atlas));
        pass.setUniform("Sampler2",Minecraft.getInstance().gameRenderer.levelLightmap(),atlas.getSampler());
        pass.setUniform("DynamicTransforms",profiledTransform(model,new Vector4f(1),new Vector3f(x/16f,0,z/16f),texture));
        pass.setVertexBuffer(0,seam.buffer().slice());
        if(settings.drawCalls()) pass.draw(seam.vertices(),1,0,0);
        long triangles=seam.vertices()/3L;
        activeTriangles+=triangles;
        frustumTriangles+=triangles;
        drawnTriangles+=triangles;
        hiZ.counters.add(-1,triangles,false);
        return 1;
    }

    private void releaseVanillaSeam() {
        var old=vanillaSeam;
        vanillaSeam=null;
        patchedSeamChunks.clear();
        seamEdges=List.of();
        seamSources=List.of();
        seamDrawn=null;
        seamDirty=true;
        if(old!=null) RenderSystem.queueFencedTask(old::close);
    }

    private record VanillaSeam(GpuBuffer buffer,ByteBudget.Reservation lease,int originX,int originZ,int vertices) {
        void close() { try { buffer.close(); } finally { lease.close(); } }
    }

    private CoverageBuffer updateCoverageBuffer() {
        if(emptyCoverageBuffer==null) {
            emptyCoverageBuffer=coverageBuffer(ByteBuffer.allocate(VanillaCoverage.BYTES));
            if(emptyCoverageBuffer==null) { resourceLimit=true; status="GPU coverage buffer budget exhausted"; return null; }
        }
        var next=vanillaCoverage.data(patchedSeamChunks);
        if(coverageBuffer!=null && next.equals(lastCoverage)) return coverageBuffer;
        var replacement=coverageBuffer(next);
        if(replacement==null) return emptyCoverageBuffer; // Keep LOD while retirement frees space.
        var old=coverageBuffer;
        coverageBuffer=replacement; lastCoverage=ByteBuffer.allocate(next.remaining()).put(next.duplicate()).flip();
        retireCoverage(old);
        return replacement;
    }
    private CoverageBuffer coverageBuffer(ByteBuffer data) {
        var lease=gpuMemory.tryReserve(data.remaining());
        if(lease==null) return null;
        try { return new CoverageBuffer(GpuTile.upload(data.duplicate(),GpuBuffer.USAGE_UNIFORM),lease); }
        catch(RuntimeException failure) { lease.close(); throw failure; }
    }
    private void retireCoverage(CoverageBuffer old) {
        if(old==null) return;
        retiringBytes.addAndGet(VanillaCoverage.BYTES);
        RenderSystem.queueFencedTask(() -> {
            try { old.close(); } finally { retiringBytes.addAndGet(-VanillaCoverage.BYTES); }
        });
    }
    private record CoverageBuffer(GpuBuffer buffer,ByteBudget.Reservation memory) implements AutoCloseable {
        public void close() { try { buffer.close(); } finally { memory.close(); } }
    }

    private void uploadStreaming(TileCoordinator active) {
        long start=System.nanoTime(),uploaded=0;
        var newTiles=new java.util.LinkedHashMap<TileKey,GpuTile>();
        long uploadBudget=dev.faraway.k7q4m9.client.FarAwayClient.benchmarkLocked()?4_000_000L:1_000_000L;
        while(System.nanoTime()-start<uploadBudget) {
            if(heldUpload==null) heldUpload=active.pollCompleted();
            if(heldUpload==null) break;
            if(!heldUpload.isCurrent()) { heldUpload.close(); heldUpload=null; continue; }
            TileMesh mesh=heldUpload.mesh();
            if(uploaded>0 && uploaded+mesh.bytes()>4*1024*1024) break;
            var lease=gpuMemory.tryReserve(1);
            if(lease==null) {
                // Drop idle/prefetch first; requested coverage and benchmark quality stay fixed.
                if(active.trimToWanted()) {
                    var idle=new ArrayList<GpuTile>();
                    tiles.removeIf(tile -> { if(!active.retained(tile.key)) { idle.add(tile); return true; } return false; });
                    retire(idle);
                    if(!heldUpload.isCurrent()) { heldUpload.close(); heldUpload=null; }
                    break;
                }
                // In-flight retirement is temporary backpressure, not a failed candidate.
                if(retiringBytes.get()==0) { resourceLimit=true; status="Required GPU working set exceeds byte budget"; }
                break;
            }
            try {
                long uploadStart=heldUpload.uploadStarted();
                GpuTile gpu=new GpuTile(mesh,lease,heldUpload.generationNanos(),gpuMemory,geometryArena);
                var duplicate=newTiles.put(mesh.key(),gpu);
                if(duplicate!=null) retire(java.util.List.of(duplicate));
                heldUpload.uploaded(uploadStart); uploaded+=mesh.bytes(); RenderProfile.INSTANCE.uploaded(mesh.bytes());
                dev.faraway.k7q4m9.diagnostics.TileLogSummary.uploaded(Integer.numberOfTrailingZeros(mesh.key().spacing()),mesh.bytes());
                dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.debug("FarAway GPU upload lod={} key={} bytes={} vertices={} triangles={} format={}",Integer.numberOfTrailingZeros(mesh.key().spacing()),mesh.key(),mesh.bytes(),mesh.vertexCount()+mesh.waterVertexCount(),mesh.triangleCount(),mesh.quadCount()>0?"quad40":mesh.vertexStride()==8?"grid8":"precise16");
            } catch(PlaneAtlas.BudgetUnavailable waiting) {
                heldUpload.uploadDeferred(); status="Waiting for GPU memory for plane replacement";
            } catch(RuntimeException failure) {
                heldUpload.uploadFailed(failure);
                dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.error("Terrain upload failed",failure);
            } finally { heldUpload.close(); heldUpload=null; }
        }
        if(!newTiles.isEmpty()) {
            // Trimming can invalidate work that was uploaded earlier in this same batch.
            var discarded=new ArrayList<GpuTile>();
            newTiles.values().removeIf(tile -> { if(!active.retained(tile.key)) { discarded.add(tile); return true; } return false; });
            retire(discarded);
            if(!newTiles.isEmpty()) {
                visibleCacheValid=false;
                var replaced=new ArrayList<GpuTile>();
                tiles.removeIf(old -> { if(newTiles.containsKey(old.key)) { replaced.add(old); return true; } return false; });
                retire(replaced); tiles.addAll(newTiles.values()); seamDirty=true;
            }
        }
        lastUploadNanos=System.nanoTime()-start; RenderProfile.INSTANCE.add(UPLOAD,lastUploadNanos);
    }
    public Metrics metrics() {
        var active=streaming;
        return new Metrics(radiusChunks,active==null?0:active.wanted().size(),active==null?0:active.residentCount(),
            drawnTiles,gpuMemory.used(),active==null?0:active.pendingBytes(),active==null?0:active.oldestMissingNanos(),
            active==null?0:active.failures(),resourceLimit,lastUploadNanos,gpuMemory.peak(),active==null?0:active.pendingPeak(),prefetchReady(),vanillaCoverage.readyColumns());
    }
    public HiZCounters.Snapshot hiZCounters(){return hiZ.counters.snapshot();}
    public HudMetrics hudMetrics() {
        var active=streaming;
        long ramTriangles=active==null?0:active.queuedTriangles();
        if(heldUpload!=null && heldUpload.isCurrent()) ramTriangles+=heldUpload.mesh().triangleCount();
        var waiting=pending.get(); if(waiting!=null && waiting.epoch==epoch) for(var mesh:waiting.meshes) ramTriangles+=mesh.triangleCount();
        return new HudMetrics(hudResidentTiles,drawnTiles,ramTriangles,residentTriangles,drawnTriangles,residentSamples,displayedSamples,renderedSamples);
    }
    public LodTimingStats.Summary[] lodTimingSnapshot() {
        return residentLodTimings.clone();
    }
    public int[][] lodProgress() {
        var active=streaming;
        var settings=dev.faraway.k7q4m9.client.LodSettings.get();
        return active==null?new int[19][2]:active.lodProgress(settings.minLodVisible(),settings.maxLodVisible());
    }
    private static long horizontalSamples(TileKey key) { long side=key.blocks()/key.spacing(); return side*side; }
    public record HudMetrics(int residentTiles,int drawnTiles,long ramTriangles,long residentTriangles,long drawnTriangles,long residentSamples,long displayedSamples,long renderedSamples) {}
    public record Metrics(int radiusChunks,int wanted,int resident,int drawn,long gpuBytes,long pendingBytes,
                          long oldestMissingNanos,int failed,boolean resourceLimit,long uploadNanos,long gpuPeakBytes,long pendingPeakBytes,
                          boolean prefetchReady,int vanillaReadyColumns) {
        public boolean fullCoverage() { return wanted>0 && resident==wanted && failed==0 && !resourceLimit; }
    }
    private void retire() {
        visibleCacheValid=false;
        if (tiles.isEmpty()) return;
        var old = List.copyOf(tiles); tiles.clear();
        retire(old);
    }
    private void retire(List<GpuTile> old) {
        if(old.isEmpty()) return;
        seamDirty=true;
        long bytes=old.stream().mapToLong(tile -> tile.bytes).sum();
        retiringBytes.addAndGet(bytes);
        RenderSystem.queueFencedTask(() -> {
            try { old.forEach(GpuTile::close); }
            finally { retiringBytes.addAndGet(-bytes); }
        });
    }
    public void close() {
        blockTextureFiltering.close();
        mipmappedBlockAtlas.close();
        geometryArena.flushRetiredCommands();
        releaseHiZ();reset(); FarAwayView.distanceBlocks=0; retire();
        retireCoverage(coverageBuffer); retireCoverage(emptyCoverageBuffer);
        coverageBuffer=null; emptyCoverageBuffer=null;
        if(previewLines!=null) { previewLines.close(); previewLines=null; }
        if(previewLineLease!=null){previewLineLease.close();previewLineLease=null;}
        previewLineCount=0;uploadedPreviewSquares=uploadedPreviewEdges=null;
        if(planeVertices!=null) { planeVertices.close(); planeVertices=null; }
        if(frustumLines!=null) { frustumLines.close(); frustumLines=null; }
        if(frustumArrow!=null) { frustumArrow.close(); frustumArrow=null; } uploadedFrustum=null;
        retirePlaneBuffer(); submitPlaneRetirements();
    }
    private record Pending(long epoch,List<TileMesh> meshes) {}

    private static final class GpuTile implements AutoCloseable {
        double occluderDistance;
        final TileKey key;
        final dev.faraway.k7q4m9.mesh.VoxelSurface voxelSurface;
        final dev.faraway.k7q4m9.mesh.MeshPerimeter perimeter;
        AABB bounds;
        final int indexCount, waterCount,terrainCount,indexStride,quadCount;
        final boolean grid;
        final long bytes;
        final long generationNanos;
        final ByteBudget.Reservation memory;
        GpuBuffer vertices,indices,water;
        PlaneAtlas.Slot plane;
        GeometryArena.Slot sharedTerrain,sharedWater,sharedQuads;
        long triangles() { return plane!=null?2:(indexCount>0?indexCount:terrainCount)/3L+waterCount/3L+quadCount*2L; }
        GpuTile(TileMesh mesh,ByteBudget.Reservation memory,long generationNanos,ByteBudget budget,GeometryArena arena) {
            this.memory=memory;
            this.generationNanos=generationNanos;
            quadCount=mesh.quadCount();terrainCount=mesh.vertexCount(); grid=mesh.vertexStride()==8; indexStride=mesh.indexStride();
            key = mesh.key(); voxelSurface=mesh.voxelSurface(); perimeter=mesh.perimeter(); bytes=mesh.bytes(); indexCount=mesh.indexCount(); waterCount=mesh.waterVertexCount();
            bounds=new AABB(key.originX(),mesh.minY(),key.originZ(),key.originX()+key.blocks(),mesh.maxY()+1,key.originZ()+key.blocks());
            try {
                if(mesh.farPlane()!=null) {
                    long start=System.nanoTime();
                    try { plane=PlaneAtlas.upload(mesh.farPlane(),budget); }
                    finally { RenderProfile.INSTANCE.add(UPLOAD_TEXTURE,System.nanoTime()-start); }
                }
                long geometryStart=System.nanoTime();
                try {
                    if(mesh.vertexCount()>0) sharedTerrain=arena.upload(mesh,false);
                    if(quadCount>0)sharedQuads=arena.uploadQuads(mesh);
                    if(waterCount>0)sharedWater=arena.upload(mesh,true);
                    if(sharedTerrain!=null||sharedWater!=null||sharedQuads!=null){
                        float low=Float.POSITIVE_INFINITY,high=Float.NEGATIVE_INFINITY;
                        if(sharedTerrain!=null){low=Math.min(low,sharedTerrain.minY);high=Math.max(high,sharedTerrain.maxY);}
                        if(sharedQuads!=null){low=Math.min(low,sharedQuads.minY);high=Math.max(high,sharedQuads.maxY);}
                        if(sharedWater!=null){low=Math.min(low,sharedWater.minY);high=Math.max(high,sharedWater.maxY);}
                        // Storage allocation height is not a geometry bound. Keep only the
                        // actual uploaded geometry, including water, structures and foliage.
                        bounds=new AABB(key.originX(),low,key.originZ(),key.originX()+key.blocks(),high,key.originZ()+key.blocks());
                    }
                } finally { RenderProfile.INSTANCE.add(UPLOAD_BUFFER,System.nanoTime()-geometryStart); }
            } catch(RuntimeException e) { close(); throw e; }
        }
        private static GpuBuffer upload(ByteBuffer source,int usage) {
            long start=System.nanoTime();
            ByteBuffer nativeData=MemoryUtil.memAlloc(source.remaining());
            try {
                nativeData.put(source.duplicate()).flip();
                return RenderSystem.getDevice().createBuffer(() -> "FarAway tile",usage,nativeData);
            } finally { MemoryUtil.memFree(nativeData); RenderProfile.INSTANCE.add(UPLOAD_BUFFER,System.nanoTime()-start); }
        }
        public void close() {
            if(sharedTerrain!=null){sharedTerrain.close();sharedTerrain=null;}
            if(sharedWater!=null){sharedWater.close();sharedWater=null;}
            if(sharedQuads!=null){sharedQuads.close();sharedQuads=null;}
            if(plane!=null) { plane.close(); plane=null; }
            if(vertices!=null) vertices.close();
            if(indices!=null) indices.close();
            if(water!=null) water.close();
            memory.close();
        }
    }
}
