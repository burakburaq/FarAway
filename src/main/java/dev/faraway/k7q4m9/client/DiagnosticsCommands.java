package dev.faraway.k7q4m9.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import dev.faraway.k7q4m9.diagnostics.FrameRecorder;
import dev.faraway.k7q4m9.diagnostics.GameFpsRecorder;
import dev.faraway.k7q4m9.diagnostics.DistanceBenchmark;
import dev.faraway.k7q4m9.diagnostics.DistanceSearch;
import dev.faraway.k7q4m9.diagnostics.CoverageRecorder;
import dev.faraway.k7q4m9.worldgen.WorldTerrainSampler;
import dev.faraway.k7q4m9.worldgen.WorldDefinition;
import dev.faraway.k7q4m9.render.PreviewRenderer;
import dev.faraway.k7q4m9.terrain.TileKey;
import dev.faraway.k7q4m9.terrain.LodTile;
import dev.faraway.k7q4m9.mesh.TerrainMeshBuilder;

public final class DiagnosticsCommands {
    private final CoverageRecorder coverageRecorder=new CoverageRecorder();
    private java.util.Map<String,String> captureStartProfile=java.util.Map.of();
    private final FrameRecorder recorder = new FrameRecorder(300_000);
    private final GameFpsRecorder fpsRecorder = new GameFpsRecorder(36_000);
    private final AtomicLong session = new AtomicLong();
    private final PreviewRenderer preview = new PreviewRenderer();
    private long nextLodHudUpdate;
    private final long[] hudFrameTimes=new long[512];
    private long previousHudFrame,nextHudFpsUpdate;
    private long nextFrameProfileLog;
    private int hudFrameCount,hudFrameNext;
    private String hudLowFps="--";
    private String[] lodHudLines=new String[0];
    private int[] lodHudColors=new int[19];
    private String[] lodHudTimes=new String[19];
    private int[] lodHudTimeColors=new int[19];
    private int smokePhase;
    private long autoStartedSession=Long.MIN_VALUE;
    private boolean openLodSettings;
    private boolean observedGpuSampling=LodSettings.get().gpuSurfaceSampling();
    private boolean observedGpuUnavailable;
    private boolean observedGeneratePlanes=LodSettings.get().generatePlaneTiles(),observedGenerateVoxels=LodSettings.get().generateVoxelTiles();
    private boolean observedStructures=LodSettings.get().generateStructures(),observedTrees=LodSettings.get().generateTrees();
    private boolean observedVoxel=LodSettings.get().voxelTerrain();
    private int observedBlendMaxLod=LodSettings.get().biomeBlendMaxLod();
    private int observedPlaneThreshold=LodSettings.get().planeThreshold();
    private dev.faraway.k7q4m9.streaming.LodScatter.Config observedScatter=new dev.faraway.k7q4m9.streaming.LodScatter.Config(LodSettings.get().scatterStrength(),LodSettings.get().scatterScale(),LodSettings.get().scatterSeedOffset(),LodSettings.get().noiseBottom(),LodSettings.get().noiseTop(),LodSettings.get().scatterMode(),LodSettings.get().noiseEnabled());
    private boolean observedEnabled=LodSettings.get().enabled();
    private long smokePhaseStart;
    private DistanceBenchmark benchmark;
    private record FillBenchmark(long start,long session,net.minecraft.world.phys.Vec3 origin,float yaw,float pitch,int previousDistance,float health,float absorption) {}
    private int benchmarkProbePhase;
    private long benchmarkProbeTick;
    private void benchmarkProbe(Minecraft client) {
        if(!Boolean.getBoolean("faraway.benchmarkProbe") || client.level==null || client.player==null || client.getSingleplayerServer()==null || benchmarkProbePhase>=2)return;
        if(++benchmarkProbeTick<60)return;
        if(benchmarkProbePhase==0) {
            client.getWindow().setWindowed(1920,1080);
            var server=client.getSingleplayerServer();String id=client.player.getUUID().toString();
            server.execute(() -> {
                var commands=server.getCommands();var source=server.createCommandSourceStack();
                commands.performPrefixedCommand(source,"gamemode spectator "+id);
                commands.performPrefixedCommand(source,"weather clear");
                commands.performPrefixedCommand(source,"tp "+id+" 0.0 1000.0 0.0 0 0");
            });
            benchmarkProbePhase=1;benchmarkProbeTick=0;return;
        }
        if(client.player.position().distanceToSqr(new net.minecraft.world.phys.Vec3(0,1000,0))>.0001)return;
        client.player.setYRot(0);client.player.setXRot(0);client.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        if(benchmark((FabricClientCommandSource)client.getConnection().getSuggestionsProvider(),MAX_BENCHMARK_DISTANCE)>0) {
            benchmarkProbePhase=2;FarAwayClient.LOGGER.info("FarAway benchmark probe started position=0,1000,0 yaw=0 pitch=0 distance={}",MAX_BENCHMARK_DISTANCE);
        }
    }
    private FillBenchmark fillBenchmark;
    private net.minecraft.world.scores.Scoreboard benchmarkScoreboard;
    private net.minecraft.world.scores.Objective benchmarkSidebar,previousSidebar;
    private net.minecraft.world.BossEvent benchmarkBossBar;
    private long benchmarkBarRemoveAt;
    private DistanceSearch search;
    private FabricClientCommandSource searchSource;
    private int pendingSearchRadius;
    private net.minecraft.world.phys.Vec3 searchOrigin;
    private float searchYaw,searchPitch;
    private long pendingSearchSince;
    private int searchRestoreRadius;
    private boolean searchRestoreStreaming;
    private java.util.Map<String,String> searchSettings;
    private String activeCaptureId="";
    private net.minecraft.client.InactivityFpsLimit searchInactivity;
    private static final int REFERENCE_WIDTH=1920,REFERENCE_HEIGHT=1080;
    private static final int MAX_BENCHMARK_DISTANCE=5_303_300;
    private Runnable pendingProfileStart;
    private boolean referenceResizeBypassed,referenceResizeFailureNotice;
    private long profileStartSession,profileDeadline;
    private int profileStableTicks;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "FarAway diagnostics");
        thread.setDaemon(true);
        return thread;
    });

    public void tickBlockTextures() { preview.tickBlockTextures(); }

    public void prepareFrame() {
        lockBenchmarkPlayer(Minecraft.getInstance());
        long now=System.nanoTime();
        if(Minecraft.getInstance().level==null || previousHudFrame==0 || now-previousHudFrame>2_000_000_000L) {
            hudFrameCount=0; hudFrameNext=0; hudLowFps="--";
        } else {
            hudFrameTimes[hudFrameNext]=now-previousHudFrame; hudFrameNext=(hudFrameNext+1)%hudFrameTimes.length;
            hudFrameCount=Math.min(hudFrameCount+1,hudFrameTimes.length);
            if(now>=nextHudFpsUpdate) {
                long[] sorted=java.util.Arrays.copyOf(hudFrameTimes,hudFrameCount); java.util.Arrays.sort(sorted);
                int count=Math.max(1,(hudFrameCount+99)/100); double sum=0;
                for(int i=sorted.length-count;i<sorted.length;i++) sum+=sorted[i];
                hudLowFps=sum>0?Long.toString(Math.round(1_000_000_000.0*count/sum)):"--";
                nextHudFpsUpdate=now+1_000_000_000L;
                if(now>=nextFrameProfileLog && LodSettings.get().enabled()) {
                    FarAwayClient.LOGGER.info("FarAway frame profile fps={} low1Percent={} samples={} windowFrames={}",Minecraft.getInstance().getFps(),hudLowFps,count,hudFrameCount);
                    nextFrameProfileLog=now+10_000_000_000L;
                }
            }
        }
        previousHudFrame=now; preview.prepareFrame();
    }
    public void renderPreviewOverlay(com.mojang.blaze3d.pipeline.RenderTarget target,net.minecraft.client.renderer.state.level.CameraRenderState camera) { preview.renderPreviewOverlay(target,camera); }
    public void renderDeferred(com.mojang.blaze3d.pipeline.RenderTarget target,net.minecraft.client.renderer.state.level.CameraRenderState camera) { preview.renderDeferred(target,camera); }
    public void renderOpaque(com.mojang.renderpearl.api.commands.RenderPass pass,net.minecraft.client.renderer.state.level.CameraRenderState camera) {
        preview.renderOpaque(pass,camera);
    }

    public void register() {
        FarAwayKeyBindings.register();
        preview.register();
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
            net.minecraft.resources.Identifier.parse("faraway:performance"),(graphics,delta) -> {
                var client=Minecraft.getInstance();
                if(client.level!=null && Freecam.active() && !client.gui.hud.isHidden()) {
                    String multiplier=String.format(java.util.Locale.ROOT,"Speed Multiplier: %.2f",Freecam.speedExponent());
                    graphics.centeredText(client.font,Component.literal(multiplier),graphics.guiWidth()/2,graphics.guiHeight()-65,0xffffffff);
                    String speed=String.format(java.util.Locale.ROOT,"%.1f blocks/s",Freecam.speed());
                    graphics.centeredText(client.font,Component.literal(speed),graphics.guiWidth()/2,graphics.guiHeight()-55,0xffffffff);
                }
                if(client.level==null) return;
                var settings=LodSettings.get();
                 if(settings.advancedHud() || settings.hiZDetailsHud()) {
                     var lines=new java.util.ArrayList<String>();
                     if(settings.advancedHud()) {
                         double cpuAvg=dev.faraway.k7q4m9.diagnostics.AdvancedRenderTiming.cpuAverageNs(),gpuAvg=dev.faraway.k7q4m9.diagnostics.AdvancedRenderTiming.gpuAverageNs(),submissionAvg=dev.faraway.k7q4m9.diagnostics.AdvancedRenderTiming.submissionAverageNs();
                         java.util.Collections.addAll(lines,"Draw Calls/f: "+groupDigits(preview.triangleStages().calls()),
                             "Draw Calls/s: "+groupDigits(preview.drawCallsPerSecond()),"","Avg: 1s",
                             timingLabel("CPU Render Time",cpuAvg),timingLabel("GPU Render Time",gpuAvg),timingLabel("Draw Submission Time",submissionAvg));
                     }
                     if(settings.hiZDetailsHud()) {
                         boolean drawing=settings.renderTiles()&&settings.drawCalls(),hiZ=LodSettings.hiZActive();
                         var tileCounts=preview.hiZCounters();
                         String prePlanes=!drawing?"0":hiZ?(tileCounts==null?"--":groupDigits(tileCounts.prePlanes())):groupDigits(preview.prePlaneTiles());
                         String preVoxels=!drawing?"0":hiZ?(tileCounts==null?"--":groupDigits(tileCounts.preVoxels())):groupDigits(preview.preVoxelTiles());
                         String postPlanes=hiZ?(tileCounts==null?"--":groupDigits(tileCounts.postPlanes())):prePlanes;
                         String postVoxels=hiZ?(tileCounts==null?"--":groupDigits(tileCounts.postVoxels())):preVoxels;
                         if(!lines.isEmpty()) lines.add("");
                         java.util.Collections.addAll(lines,"Pre Hi-Z","Plane Tiles: "+prePlanes,"Voxel Tiles: "+preVoxels,
                             "","Post Hi-Z","Plane Tiles: "+postPlanes,"Voxel Tiles: "+postVoxels);
                     }
                     if(settings.advancedHud() && settings.generation().poolEnabled()) {
                         var pool=preview.tileGenPoolSnapshot();
                         java.util.Collections.addAll(lines,"","Waiting Tiles: "+pool.waiting()+"/"+pool.limit(),
                             String.format(java.util.Locale.ROOT,"Waiting Tile Memory: %.1f/%d MiB",pool.bytes()/1048576.0,pool.byteLimit()/1048576));
                     }
                     int timingY=5;
                     for(String line:lines) {
                         graphics.text(client.font,line,graphics.guiWidth()-5-client.font.width(line),timingY,0xffffffff,true);
                         timingY+=10;
                     }
                 }
                int y=5;
                 if(settings.performanceHud()) {
                    var runtime=Runtime.getRuntime();
                    double used=(runtime.totalMemory()-runtime.freeMemory())/(1024.0*1024*1024);
                    double maximum=runtime.maxMemory()/(1024.0*1024*1024);
                    long gpuUsed=preview.metrics().gpuBytes()/(1024*1024);
                    long gpuBudget=Math.round(LodSettings.effectiveVramGiB()*1024);
                    String[] lines={"FPS:"+client.getFps()+", 1%:"+hudLowFps,String.format(java.util.Locale.ROOT,"Mem: %.1f/%.1fG",used,maximum),
                        "VRAM: "+gpuUsed+"/"+gpuBudget+"M"};
                    for(String line:lines) { graphics.text(client.font,line,5,y,0xffffffff,true); y+=10; }
                     y+=10;
                     graphics.text(client.font,"Workers: "+preview.activeWorkerLimit()+"/"+LodSettings.workerLimit(),5,y,0xffffffff,true); y+=10;
                }
                 var triangleMode=settings.triangleHudMode();
                 if((settings.enabled() || settings.renderTiles()) && triangleMode!=LodSettings.TriangleHudMode.OFF) {
                    if(y>5) y+=10;
                     var metrics=preview.hudMetrics();
                    var counters=preview.hiZCounters();boolean hiZ=LodSettings.hiZActive();
                    var stages=preview.triangleStages();
                    long activeTriangles=hiZ&&counters!=null?counters.activeTriangles():stages.active();
                    long frustumTriangles=hiZ&&counters!=null?counters.frustumTriangles():stages.frustum();
                    long faceTriangles=hiZ&&counters!=null?counters.preTriangles():stages.backface();
                     String rendered=hiZ?(counters==null?"--":groupDigits(counters.postTriangles())):groupDigits(settings.drawCalls()?faceTriangles:0);
                     var lines=new java.util.ArrayList<>(java.util.List.of("Triangle Counts:",groupDigits(metrics.ramTriangles())+" on CPU",
                         groupDigits(metrics.residentTriangles())+" on GPU"));
                     if(triangleMode==LodSettings.TriangleHudMode.DETAILED) {
                         java.util.Collections.addAll(lines,groupDigits(activeTriangles)+" active","",
                             groupDigits(frustumTriangles)+" post frustum culling",groupDigits(faceTriangles)+" post backface culling",
                             rendered+" rendered","",stageCulled(activeTriangles,frustumTriangles,"frustum"),
                             stageCulled(frustumTriangles,faceTriangles,"backface"),
                             hiZ?(counters==null?"-- Hi-Z culled":stageCulled(counters.preTriangles(),counters.postTriangles(),"Hi-Z")):"0 Hi-Z culled (0.0%)",
                             "","Horizontal Samples:",groupDigits(metrics.residentSamples())+" in memory",groupDigits(metrics.residentSamples())+" on GPU",
                             hiZ?(counters==null?"-- final":groupDigits(counters.postSamples())+" final"):groupDigits(settings.drawCalls()?metrics.renderedSamples():0)+" final",
                             hiZ?(counters==null?"--% culled":culledLabel(counters.sampleCulled())):"0.0% culled");
                     } else lines.add(rendered+" rendered");
                    for(String line:lines) { graphics.text(client.font,line,5,y,0xffffffff,true); y+=10; }
                }
                 if(settings.tileDetailHud()) {
                    if(y>5) y+=10;
                    if(System.nanoTime()>=nextLodHudUpdate) {
                        nextLodHudUpdate=System.nanoTime()+1_000_000_000L;
                        var rows=preview.lodTimingSnapshot(); var formatted=new String[19];
                        for(int level=0;level<formatted.length;level++) {
                             formatted[level]="s0t0D"+level;
                            lodHudTimes[level]="--";
                            lodHudTimeColors[level]=0xffffffff;
                        }
                        for(var row:rows) {
                            int level=Integer.numberOfTrailingZeros(row.detail());
                             String sectors=java.math.BigDecimal.valueOf(row.sectorCount()).setScale(3,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
                             formatted[level]="s"+sectors+"t"+row.tileCount()+"D"+level;
                            if(row.tileCount()>0) {
                                 long ms=Math.round(row.averageMsPerSector());
                                lodHudTimes[level]=ms+"ms";
                                lodHudTimeColors[level]=ms<500?0xff008000:ms<1000?0xff55ff55:
                                    ms<2500?0xffffff55:ms<5000?0xffffaa00:0xffff5555;
                            }
                        }
                        lodHudLines=formatted;
                        var progress=preview.lodProgress();
                        for(int level=0;level<progress.length;level++) {
                            int planned=progress[level][0],ready=progress[level][1];
                            lodHudColors[level]=planned==0?0xffff5555:ready>=planned?0xff008000:
                                (long)ready*10 >= (long)planned*7?0xff55ff55:0xffffff55;
                        }
                    }
                     graphics.text(client.font,"LoD (avg ms/sector)",5,y,0xffffffff,true); y+=10;
                    int rowsPerColumn=Math.max(1,(graphics.guiHeight()-y)/10);
                     int labelWidth=0,timeWidth=0,separatorWidth=client.font.width(": ");
                    for(String label:lodHudLines) labelWidth=Math.max(labelWidth,client.font.width(label));
                     for(int i=0;i<lodHudLines.length;i++) timeWidth=Math.max(timeWidth,client.font.width(lodHudTimes[i]));
                     int columnWidth=labelWidth+separatorWidth+timeWidth+10;
                    for(int i=0;i<lodHudLines.length;i++) {
                         int x=5+(i/rowsPerColumn)*columnWidth, rowY=y+(i%rowsPerColumn)*10;
                         graphics.text(client.font,lodHudLines[i],x,rowY,lodHudColors[i],true);
                        x+=labelWidth;
                        graphics.text(client.font,":",x,rowY,0xffffffff,true);
                         graphics.text(client.font,lodHudTimes[i],x+separatorWidth,rowY,lodHudTimeColors[i],true);
                    }
                }
            });
        net.fabricmc.fabric.api.resource.v1.ResourceLoader.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
            .registerReloadListener(net.minecraft.resources.Identifier.parse("faraway:render_revision"),
                (net.minecraft.server.packs.resources.ResourceManagerReloadListener) resources -> {
                    var client=Minecraft.getInstance();
                    client.execute(() -> {
                        cancelBenchmark("client resources reloaded");
                        boolean resume=preview.isStreaming();
                        int radius=preview.metrics().radiusChunks();
                        preview.reset();
                        dev.faraway.k7q4m9.mesh.TextureMaterial.resetModels();
                        if(resume && client.level!=null) stream(client,radius);
                    });
                });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            FarAwayKeyBindings.Action shortcut;
            while((shortcut=FarAwayKeyBindings.poll())!=null) {
                if(client.level==null || client.gui.screen()!=null)continue;
                switch(shortcut) {
                    case FREECAM -> Freecam.toggleFreecam();
                    case DEBUGCAM -> Freecam.toggle();
                    case SETTINGS -> openLodSettings=true;
                }
            }
            boolean enabled=LodSettings.get().enabled();
            var scatter=new dev.faraway.k7q4m9.streaming.LodScatter.Config(LodSettings.get().scatterStrength(),LodSettings.get().scatterScale(),LodSettings.get().scatterSeedOffset(),LodSettings.get().noiseBottom(),LodSettings.get().noiseTop(),LodSettings.get().scatterMode(),LodSettings.get().noiseEnabled());
            if(!scatter.equals(observedScatter)) { observedScatter=scatter; cancelBenchmark("LOD scatter settings changed"); }
            if(observedBlendMaxLod!=LodSettings.get().biomeBlendMaxLod()) {
                observedBlendMaxLod=LodSettings.get().biomeBlendMaxLod();
                cancelBenchmark("Biome blend LOD changed"); preview.close(); autoStartedSession=Long.MIN_VALUE;
            }
            boolean gpuUnavailable=dev.faraway.k7q4m9.worldgen.GpuSurfaceSampler.unavailable();
            if(observedStructures!=LodSettings.get().generateStructures() || observedTrees!=LodSettings.get().generateTrees()) {
                observedStructures=LodSettings.get().generateStructures();observedTrees=LodSettings.get().generateTrees();cancelBenchmark("Decoration settings changed");
            }
            if(observedGeneratePlanes!=LodSettings.get().generatePlaneTiles() || observedGenerateVoxels!=LodSettings.get().generateVoxelTiles()) {
                observedGeneratePlanes=LodSettings.get().generatePlaneTiles();observedGenerateVoxels=LodSettings.get().generateVoxelTiles();
                cancelBenchmark("Tile generation types changed");
            }
            if(observedPlaneThreshold!=LodSettings.get().planeThreshold()) {
                observedPlaneThreshold=LodSettings.get().planeThreshold();
                cancelBenchmark("Plane threshold changed");
                preview.setPlaneThreshold(observedPlaneThreshold);
            }
            if(observedVoxel!=LodSettings.get().voxelTerrain() || observedGpuSampling!=LodSettings.get().gpuSurfaceSampling() || gpuUnavailable!=observedGpuUnavailable) {
                observedGpuUnavailable=gpuUnavailable;
                observedGpuSampling=LodSettings.get().gpuSurfaceSampling();
                observedVoxel=LodSettings.get().voxelTerrain();
                cancelBenchmark("Terrain representation changed");
                preview.close(); autoStartedSession=Long.MIN_VALUE;
            }
            if(enabled!=observedEnabled) {
                observedEnabled=enabled;
                if(!enabled)cancelBenchmark("Tile generation paused");
            }
            if((enabled || LodSettings.get().renderTiles()) && !Boolean.getBoolean("faraway.smoke") && client.level!=null
                && client.player!=null && client.getSingleplayerServer()!=null
                && autoStartedSession!=session.get()) {
                autoStartedSession=session.get();
                FarAwayClient.LOGGER.info("Automatically starting FarAway world coverage for session {}",autoStartedSession);
                stream(client,LodSettings.get().renderDistance());
            }
            if(openLodSettings) { openLodSettings=false; client.gui.setScreen(new LodSettingsScreen()); }
            if(pendingProfileStart!=null) {
                if(session.get()!=profileStartSession || client.level==null) pendingProfileStart=null;
                else if(client.getWindow().getWidth()==REFERENCE_WIDTH && client.getWindow().getHeight()==REFERENCE_HEIGHT) {
                    if(++profileStableTicks>=3) { var start=pendingProfileStart; pendingProfileStart=null; start.run(); }
                } else if(System.nanoTime()>profileDeadline) {
                    var start=pendingProfileStart; pendingProfileStart=null;
                    referenceResizeBypassed=true; referenceResizeFailureNotice=true;
                    start.run();
                } else profileStableTicks=0;
            }
            if(pendingProfileStart==null && pendingSearchRadius>0 && search!=null && search.active()) {
                if(client.player==null || !searchSettings.equals(settings(client))
                    || Math.abs(net.minecraft.util.Mth.wrapDegrees(client.player.getYRot()-searchYaw))>.5f
                    || Math.abs(client.player.getXRot()-searchPitch)>.5f) cancelBenchmark("search scene/profile changed");
                else if(client.player.position().distanceToSqr(searchOrigin)<.0625) {
                    int next=pendingSearchRadius; pendingSearchRadius=0;
                    if(legacyBenchmark(searchSource,next)==0) cancelBenchmark("candidate could not start");
                } else if(System.nanoTime()-pendingSearchSince>120_000_000_000L) cancelBenchmark("return to fixed scene timed out");
            }
            if(benchmark!=null) benchmark.update(System.nanoTime());
            if(benchmarkSidebar!=null && benchmarkBarRemoveAt!=0 && System.nanoTime()>=benchmarkBarRemoveAt) removeBenchmarkBar(client);
            benchmarkProbe(client);
            if(fillBenchmark!=null) {
                if(client.player==null || client.level==null || fillBenchmark.session()!=session.get()) { restoreBenchmarkDistance(); removeBenchmarkBar(client); }
                else {
                    String invalid=benchmarkInvalidReason(client,false);
                    if(invalid==null && (client.player.getHealth()<fillBenchmark.health() || client.player.getAbsorptionAmount()<fillBenchmark.absorption() || client.player.hurtTime>0)) invalid="Player took damage";
                    if(invalid==null && client.player.position().distanceToSqr(fillBenchmark.origin())>1.0e-8) invalid="Player position changed";
                    if(invalid!=null) { cancelBenchmark(invalid); client.gui.hud.getChat().addClientSystemMessage(Component.literal("FarAway benchmark cancelled: "+invalid+".")); return; }
                    lockBenchmarkPlayer(client);
                    var metrics=preview.metrics();
                    if(metrics.wanted()>0) updateBenchmarkBar(client,metrics.resident(),metrics.wanted());
                    if(preview.benchmarkCoverageReady()) {
                        updateBenchmarkBar(client,metrics.wanted(),metrics.wanted());
                        double seconds=(System.nanoTime()-fillBenchmark.start())/1_000_000_000.0;
                        writeReport(client,"fill-benchmark",java.util.Map.of("seconds",seconds,"tiles",metrics.wanted(),"position",fillBenchmark.origin().toString(),"yaw",fillBenchmark.yaw(),"pitch",fillBenchmark.pitch(),"width",client.getWindow().getWidth(),"height",client.getWindow().getHeight(),"settings",LodSettings.get().toString()));
                        restoreBenchmarkDistance();
                        benchmarkBarRemoveAt=System.nanoTime()+1_000_000_000L;
                        client.gui.hud.getChat().addClientSystemMessage(Component.literal(String.format(java.util.Locale.ROOT,"FarAway benchmark completed in %.1f seconds.",seconds)));
                    } else if(preview.benchmarkPlanTooLarge()) {
                        restoreBenchmarkDistance(); removeBenchmarkBar(client);
                        client.gui.hud.getChat().addClientSystemMessage(Component.literal("FarAway benchmark failed: tile plan exceeds the memory budget."));
                    } else if(metrics.resourceLimit() || metrics.failed()>0) {
                        restoreBenchmarkDistance();
                        removeBenchmarkBar(client);
                        client.gui.hud.getChat().addClientSystemMessage(Component.literal("FarAway benchmark failed: tile generation or resource limit."));
                    }
                }
            }
        });
        if (Boolean.getBoolean("faraway.smoke")) ClientTickEvents.END_CLIENT_TICK.register(this::smokeTick);
        LevelRenderEvents.END_MAIN.register(context -> {
            long now = System.nanoTime();
            recorder.frame(now);
            var client=Minecraft.getInstance();
            fpsRecorder.observe(now, client.getFps());
            if(recorder.active() && client.player!=null) {
                var pos=client.player.position();
                coverageRecorder.observe(now,pos.x,pos.y,pos.z,preview.metrics());
            }
        });
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
            if(level==null) { LodSettingsScreen.resetRememberedTab(); dev.faraway.k7q4m9.diagnostics.RenderProfile.INSTANCE.reset(); }
            Freecam.disable();
            cancelBenchmark("world session changed");
            session.incrementAndGet();
            preview.reset();
            if (recorder.active()) save(client, recorder.stop(), "interrupted-level-change", null);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            cancelBenchmark("client stopping");
            if (recorder.active()) save(client, recorder.stop(), "interrupted-client-stop", null);
            preview.close();
            dev.faraway.k7q4m9.worldgen.GpuSurfaceSampler.close();
            worker.shutdown();
            try {
                if(!worker.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS)) FarAwayClient.LOGGER.warn("Diagnostic export did not finish before shutdown deadline");
            } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            var faraway=dispatcher.register(literal("faraway")
                .then(literal("settings").executes(c -> { openLodSettings=true; return 1; }))
                .then(literal("clear").executes(c -> {
                    cancelBenchmark("manual clear"); preview.reset(); autoStartedSession=Long.MIN_VALUE;
                    c.getSource().sendFeedback(Component.literal("FarAway tiles cleared."));
                    return 1;
                }))
                .then(literal("status").executes(c -> {
                    c.getSource().sendFeedback(Component.literal("FarAway 26.3: " + preview.status()));
                    return 1;
                })));
            dispatcher.register(literal("fa").redirect(faraway));
        });
    }
    private static String timingLabel(String name,double ns) {
        return name+": "+(!Double.isFinite(ns)||ns<0?"--":String.format(java.util.Locale.ROOT,"%.3f",ns/1e6));
    }
    private static String stageCulled(long before,long after,String stage) {
        long removed=Math.max(0,before-after);double percent=before==0?0:100.0*removed/before;
        return groupDigits(removed)+" "+stage+String.format(java.util.Locale.ROOT," culled (%.1f%%)",percent);
    }
    private static String culledLabel(double percent) { return String.format(java.util.Locale.ROOT,percent>0&&percent<0.1?"%.4f%% culled":"%.1f%% culled",percent); }
    private static String groupDigits(long value) {
        return String.format(java.util.Locale.ROOT,"%,d",value).replace(',', ' ');
    }

    private boolean benchmarkRunning() {
        if(fillBenchmark!=null) return true;
        if(benchmark==null) return false;
        var phase=benchmark.phase();
        return phase!=DistanceBenchmark.Phase.IDLE && phase!=DistanceBenchmark.Phase.DONE && phase!=DistanceBenchmark.Phase.FAILED;
    }
    private void cancelBenchmark(String reason) {
        restoreBenchmarkDistance();
        removeBenchmarkBar(Minecraft.getInstance());
        pendingProfileStart=null; pendingSearchRadius=0;
        if(search!=null) search.cancel(reason);
        if(benchmark!=null) benchmark.cancel(reason);
    }
    private void restoreBenchmarkDistance() {
        var current=fillBenchmark;
        if(current==null) return;
        fillBenchmark=null;
        // Invalidate an asynchronous startup before restoring its previous radius.
        if(!preview.isStreaming()) preview.reset();
        preview.setRadius(current.previousDistance());
    }
    private boolean prepareReferenceProfile(Minecraft client,Runnable start) {
        if(referenceResizeBypassed) return false;
        if(client.getWindow().getWidth()==REFERENCE_WIDTH && client.getWindow().getHeight()==REFERENCE_HEIGHT) return false;
        try { client.getWindow().setWindowed(REFERENCE_WIDTH,REFERENCE_HEIGHT); }
        catch(RuntimeException failure) {
            FarAwayClient.LOGGER.warn("Unable to resize benchmark window",failure);
            referenceResizeBypassed=true; referenceResizeFailureNotice=true; return false;
        }
        pendingProfileStart=start; profileStableTicks=0; profileStartSession=session.get();
        profileDeadline=System.nanoTime()+5_000_000_000L;
        return true;
    }

    private int search(FabricClientCommandSource source,int ceiling) {
        if(ceiling%32!=0) { source.sendError(Component.literal("Search ceiling must be a multiple of 32 chunks")); return 0; }
        if(source.getClient().getSingleplayerServer()==null || source.getClient().player==null || !source.getClient().player.isSpectator() || recorder.active()) {
            source.sendError(Component.literal("Search requires an idle capture and a singleplayer spectator test scene")); return 0;
        }
        cancelBenchmark("new search");
        if(prepareReferenceProfile(source.getClient(),() -> search(source,ceiling))) return 1;
        searchSource=source;
        searchOrigin=source.getClient().player.position();
        searchYaw=source.getClient().player.getYRot(); searchPitch=source.getClient().player.getXRot();
        searchRestoreRadius=preview.metrics().radiusChunks(); searchRestoreStreaming=preview.isStreaming();
        searchInactivity=source.getClient().options.inactivityFpsLimit().get();
        source.getClient().options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
        searchSettings=settings(source.getClient());
        search=new DistanceSearch(new DistanceSearch.Host() {
            public void runCandidate(int chunks) { pendingSearchRadius=chunks; pendingSearchSince=System.nanoTime(); }
            public void finished(DistanceSearch.Report report) {
                source.getClient().options.inactivityFpsLimit().set(searchInactivity);
                FarAwayClient.LOGGER.info("Distance search result: {}",report);
                writeReport(source.getClient(),"search",report);
                source.sendFeedback(Component.literal("Search: R_streaming="+report.highestStreamingPassedChunks()+" chunks; R_resident="+report.highestResidentPassedChunks()+" chunks; "+report.reason()));
            }
        },ceiling);
        search.start();
        source.sendFeedback(Component.literal("Scene search started: three repeats per candidate, 64 chunks upward, 32-chunk refinement; /faraway benchmark cancel stops it."));
        return 1;
    }
    private void writeReport(Minecraft client,String prefix,Object report) {
        worker.execute(() -> writeReportInline(client,prefix,report));
    }
    private void writeReportInline(Minecraft client,String prefix,Object report) {
        try {
            var file=outputDirectory(client).resolve(prefix+"-"+System.currentTimeMillis()+".json");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch(Exception e) { FarAwayClient.LOGGER.error("Benchmark report failed",e); }
    }

    public boolean benchmarkLocked() { return fillBenchmark!=null; }
    private void createBenchmarkBar(Minecraft client) {
        removeBenchmarkBar(client);
        if(client.level==null) return;
        benchmarkScoreboard=client.level.getScoreboard();
        previousSidebar=benchmarkScoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
        benchmarkSidebar=benchmarkScoreboard.addObjective("faraway_"+java.util.UUID.randomUUID().toString().replace("-", ""),net.minecraft.world.scores.criteria.ObjectiveCriteria.DUMMY,
            Component.literal("Generated"),net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER,false,net.minecraft.network.chat.numbers.BlankFormat.INSTANCE);
        benchmarkScoreboard.setDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR,benchmarkSidebar);
        benchmarkBossBar=new net.minecraft.world.BossEvent(java.util.UUID.randomUUID(),Component.literal("FarAway Benchmark: 0.0%"),net.minecraft.world.BossEvent.BossBarColor.WHITE,net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS) {};
        benchmarkBossBar.setProgress(0);
        client.gui.hud.getBossOverlay().update(net.minecraft.network.protocol.game.ClientboundBossEventPacket.createAddPacket(benchmarkBossBar));
        sidebarLine(6,Component.empty());
        sidebarLine(5,Component.literal("Planning...")); sidebarLine(4,Component.empty()); sidebarLine(3,Component.literal("0.0 sec")); sidebarLine(2,Component.literal("-- ETA")); sidebarLine(1,Component.empty());
    }
    private void updateBenchmarkBar(Minecraft client,int ready,int total) {
        updateBenchmarkBar(client,ready,total,false);
    }
    private void updateBenchmarkBar(Minecraft client,int ready,int total,boolean boundary) {
        if(benchmarkSidebar==null || total<=0) return;
        float progress=Math.min(1f,(float)ready/total);
        double eta=preview.benchmarkRemainingSeconds();
        double elapsed=fillBenchmark==null?0:(System.nanoTime()-fillBenchmark.start())/1_000_000_000.0;
        benchmarkBossBar.setProgress(progress);
        benchmarkBossBar.setName(Component.literal(String.format(java.util.Locale.ROOT,"FarAway Benchmark: %.1f%%",progress*100)));
        var overlay=client.gui.hud.getBossOverlay();
        overlay.update(net.minecraft.network.protocol.game.ClientboundBossEventPacket.createUpdateNamePacket(benchmarkBossBar));
        overlay.update(net.minecraft.network.protocol.game.ClientboundBossEventPacket.createUpdateProgressPacket(benchmarkBossBar));
        String left=Integer.toString(ready),right=Integer.toString(total),passed=String.format(java.util.Locale.ROOT,"%.1f",elapsed);
        String estimated=Double.isFinite(eta)?String.format(java.util.Locale.ROOT,"%.1f",elapsed+eta):"--";
        int half=Math.max(client.font.width("Generated")/2,Math.max(Math.max(client.font.width(left),client.font.width(right)),Math.max(Math.max(client.font.width(passed),client.font.width(estimated)),client.font.width("sec"))));
        int separator=client.font.width("/");
        sidebarLine(5,aligned(client,left,"/",right,half,separator));
        sidebarLine(3,aligned(client,passed,"","sec",half,separator));
        sidebarLine(2,aligned(client,estimated,"","ETA",half,separator));
    }
    private static Component padding(int pixels) {
        return Component.literal("\ue000".repeat(Math.max(0,pixels))).withStyle(style -> style.withFont(new net.minecraft.network.chat.FontDescription.Resource(net.minecraft.resources.Identifier.parse("faraway:spacing"))));
    }
    private static Component aligned(Minecraft client,String left,String middle,String right,int half,int separator) {
        return Component.empty().append(padding(half-client.font.width(left))).append(left)
            .append(middle.isEmpty()?padding(separator):Component.literal(middle)).append(right)
            .append(padding(half-client.font.width(right)));
    }
    private void sidebarLine(int order,Component text) {
        var score=benchmarkScoreboard.getOrCreatePlayerScore(net.minecraft.world.scores.ScoreHolder.forNameOnly("faraway_line_"+order),benchmarkSidebar);
        score.set(order); score.display(text);
    }
    private void removeBenchmarkBar(Minecraft client) {
        if(benchmarkBossBar!=null) client.gui.hud.getBossOverlay().update(net.minecraft.network.protocol.game.ClientboundBossEventPacket.createRemovePacket(benchmarkBossBar.getId()));
        benchmarkBossBar=null;
        if(benchmarkSidebar!=null) {
            if(benchmarkScoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR)==benchmarkSidebar)
                benchmarkScoreboard.setDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR,previousSidebar);
            benchmarkScoreboard.removeObjective(benchmarkSidebar);
        }
        benchmarkSidebar=null; previousSidebar=null; benchmarkScoreboard=null; benchmarkBarRemoveAt=0;
    }
    private void lockBenchmarkPlayer(Minecraft client) {
        var current=fillBenchmark;
        if(current==null || client.player==null) return;
        client.player.setYRot(current.yaw()); client.player.setXRot(current.pitch());
    }
    private String benchmarkInvalidReason(Minecraft client,boolean starting) {
        var player=client.player;
        if(client.level.isRaining() || client.level.getRainLevel(1)>0 || client.level.getThunderLevel(1)>0) return "Weather must be clear";
        if(!player.isAlive() || player.isDeadOrDying()) return "Player must be alive";
        if(player.isOnFire()) return "Player is on fire";
        if(player.isPassenger()) return "Player is riding a vehicle";
        if(player.isInWater() || player.isInLava() || player.isSwimming()) return "Player is in fluid";
        if(player.isFallFlying() || player.onClimbable()) return "Player is gliding or climbing";
        boolean flying=player.getAbilities().flying;
        if(!flying && !player.onGround()) return "Player must stand on solid ground or fly";
        if(starting) {
            var velocity=player.getDeltaMovement();
            if(velocity.horizontalDistanceSqr()>1.0e-6 || (flying && Math.abs(velocity.y)>0.001)) return "Player must be stationary";
            if(player.hurtTime>0) return "Player recently took damage";
        }
        return null;
    }
    private int benchmark(FabricClientCommandSource source,int radius) {
        if(Freecam.active()) { source.sendError(Component.literal("Disable FarAway FreeCam/DebugCam before starting a benchmark.")); return 0; }
        var client=source.getClient();
        if(client.getSingleplayerServer()==null || client.level==null || client.player==null || !client.level.dimension().equals(Level.OVERWORLD)) {
            source.sendError(Component.literal("Benchmark requires a singleplayer Overworld.")); return 0;
        }
        if(!LodSettings.get().generationEnabled()) { source.sendError(Component.literal("FarAway tile generation is disabled in settings")); return 0; }
        if(recorder.active()) { source.sendError(Component.literal("Stop the current capture first")); return 0; }
        String invalid=benchmarkInvalidReason(client,true);
        if(invalid!=null) { source.sendError(Component.literal("FarAway benchmark not started: "+invalid+".")); return 0; }
        var player=client.player;
        preview.reset();
        autoStartedSession=session.get();
        fillBenchmark=new FillBenchmark(System.nanoTime(),session.get(),player.position(),player.getYRot(),player.getXRot(),LodSettings.get().renderDistance(),player.getHealth(),player.getAbsorptionAmount());
        createBenchmarkBar(client);
        if(stream(client,radius)==0) { restoreBenchmarkDistance(); removeBenchmarkBar(client); return 0; }
        if(referenceResizeFailureNotice) source.sendFeedback(Component.literal("Cant resize window to 1920x1080, FarAway benchmark started."));
        source.sendFeedback(Component.literal("FarAway benchmark started. Movement and camera controls are locked."));
        referenceResizeFailureNotice=false;
        return 1;
    }
    private int legacyBenchmark(FabricClientCommandSource source,int radius) {
        Minecraft client=source.getClient();
        var server=client.getSingleplayerServer();
        if(server==null || client.player==null || !client.player.isSpectator()) {
            source.sendError(Component.literal("Benchmark requires a singleplayer spectator test scene; it moves 32 blocks/s and teleports 4096 blocks.")); return 0;
        }
        if(recorder.active()) { source.sendError(Component.literal("Stop the current capture first")); return 0; }
        if(benchmark!=null) benchmark.cancel("replaced by a new benchmark");
        if(prepareReferenceProfile(client,() -> legacyBenchmark(source,radius))) return 1;
        var origin=client.player.position();
        var playerId=client.player.getUUID();
        long worldSession=session.get();
        float initialYaw=client.player.getYRot(),initialPitch=client.player.getXRot();
        int restoreRadius=search!=null && search.active()?searchRestoreRadius:preview.metrics().radiusChunks();
        boolean restoreStreaming=search!=null && search.active()?searchRestoreStreaming:preview.isStreaming();
        boolean partOfSearch=search!=null && search.active();
        var previousInactivity=client.options.inactivityFpsLimit().get();
        client.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
        var fixedSettings=settings(client);
        var sceneProfile=profile(client);
        var worldDefinition=server.submit(() -> WorldDefinition.capture(server.overworld()));
        benchmark=new DistanceBenchmark(new DistanceBenchmark.Host() {
            private float expectedYaw=initialYaw;
            private void position(double x) {
                if(session.get()!=worldSession) return;
                server.execute(() -> {
                    if(session.get()!=worldSession) return;
                    var player=server.getPlayerList().getPlayer(playerId);
                    if(player!=null) player.teleportTo(x,origin.y,origin.z);
                });
            }
            public String invalidReason() {
                if(session.get()!=worldSession || client.player==null) return "world session changed";
                if(client.isPaused()) return "game paused";
                if(worldDefinition.isCompletedExceptionally()) return "unsupported world definition";
                if(Math.abs(net.minecraft.util.Mth.wrapDegrees(client.player.getYRot()-expectedYaw))>.5f
                    || Math.abs(client.player.getXRot()-initialPitch)>.5f) return "camera orientation changed";
                if(!client.player.isSpectator()) return "spectator mode changed";
                if(!fixedSettings.equals(settings(client))) return "graphics profile or camera orientation changed";
                return null;
            }
            public void resetRoute() {
                expectedYaw=initialYaw; client.player.setYRot(initialYaw); client.player.setXRot(initialPitch);
                position(origin.x);
            }
            public boolean originArrived() { return client.player!=null && client.player.position().distanceToSqr(origin)<.0625; }
            public boolean vanillaReady() {
                if(client.level==null || client.player==null || !client.levelRenderer.hasRenderedAllSections()) return false;
                int cx=Math.floorDiv(client.player.blockPosition().getX(),16),cz=Math.floorDiv(client.player.blockPosition().getZ(),16);
                int distance=client.options.getEffectiveRenderDistance();
                for(int z=cz-distance;z<=cz+distance;z++) for(int x=cx-distance;x<=cx+distance;x++) {
                    if(net.minecraft.server.level.ChunkTrackingView.isInViewDistance(cx,cz,distance,x,z)
                        && client.level.getChunkSource().getChunk(x,z,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false)==null) return false;
                }
                long now=net.minecraft.util.Util.getMillis(),fade=(long)(client.options.chunkSectionFadeInTime().get()*1000);
                for(var section:client.levelRenderer.visibleSections()) {
                    if(section.getSectionMesh()==net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED || section.getVisibility(now,fade)<1) return false;
                }
                return true;
            }
            public void clearTerrain() { preview.reset(); resetRoute(); }
            public void startCandidate(int chunks) { stream(client,chunks); }
            public void rotateView(double seconds) {
                expectedYaw=initialYaw+(float)(seconds*3);
                client.player.setYRot(expectedYaw);
            }
            public String captureId() { return activeCaptureId; }
            public boolean teleportArrived() {
                return client.player!=null && client.player.position().distanceToSqr(origin.x+60*32+4096,origin.y,origin.z)<.0625;
            }
            public void startCapture(String label) {
                long now=System.nanoTime(); DiagnosticsCommands.this.startCapture(client,now);
                FarAwayClient.LOGGER.info("Benchmark phase started: {}",label);
            }
            public GameFpsRecorder.Summary finishCapture(String label) {
                var captured=fpsRecorder.stop(); var summary=captured.summary();
                save(client,recorder.stop(),"benchmark-"+label,null,captured); return summary;
            }
            public PreviewRenderer.Metrics metrics() { return preview.metrics(); }
            public void moveAlongRoute(double seconds) { position(origin.x+seconds*32); }
            public void teleportRoute() { position(origin.x+60*32+4096); }
            public void result(DistanceBenchmark.Result result) {
                position(origin.x);
                FarAwayClient.LOGGER.info("Benchmark candidate result: {}",result);
                if(client.player!=null && session.get()==worldSession) {
                    client.player.setYRot(initialYaw); client.player.setXRot(initialPitch);
                }
                if(!partOfSearch) client.options.inactivityFpsLimit().set(previousInactivity);
                var world=worldDefinition.isDone() && !worldDefinition.isCompletedExceptionally()?worldDefinition.getNow(null):null;
                writeReport(client,"candidate-r"+radius,java.util.Map.of("profile",sceneProfile,
                    "world",world==null?java.util.Map.of("status","unavailable"):java.util.Map.of("seed",world.seed(),"fingerprint",world.fingerprint()),"result",result));
                if(search!=null && search.active()) search.accept(result);
                if((search==null || !search.active()) && session.get()==worldSession) {
                    if(restoreStreaming) stream(client,restoreRadius); else preview.reset();
                }
            }
        });
        benchmark.start(radius,System.nanoTime());
        source.sendFeedback(Component.literal(referenceResizeFailureNotice?"Cant resize window to 1920x1080, FarAway benchmark started.":"FarAway benchmark started."));
        referenceResizeFailureNotice=false;
        return 1;
    }

    private int stream(Minecraft client,int radius) {
        if(TileKey.WORLD && preview.isStreaming()) {
            preview.setRadius(radius);
            FarAwayClient.LOGGER.info("FarAway draw radius changed to {} chunks; resident LODs retained",radius);
            return 1;
        }
        var server=client.getSingleplayerServer();
        if(server==null || client.level==null) return 0;
        var dimension=client.level.dimension();
        long token=preview.reset(), worldSession=session.get();
        server.submit(() -> WorldDefinition.capture(java.util.Objects.requireNonNull(server.getLevel(dimension)))).whenComplete((world,failure) -> client.execute(() -> {
            if(session.get()!=worldSession || preview.epoch()!=token) return;
            if(failure!=null) { FarAwayClient.LOGGER.error("Terrain start rejected",failure); failFillBenchmark(client,worldSession,token); return; }
            preview.startStreaming(world,radius);
            FarAwayClient.LOGGER.info("Streaming started: radius={} dimension={} generator={} biomeSource={} sampler={} fingerprint={}",
                radius,dimension.identifier(),world.generator().getClass().getName(),world.generator().getBiomeSource().getClass().getName(),
                world.mode(),world.fingerprint());
        }));
        return 1;
    }
    private void failFillBenchmark(Minecraft client,long worldSession,long token) {
        if(fillBenchmark!=null && fillBenchmark.session()==worldSession && session.get()==worldSession && preview.epoch()==token) {
            restoreBenchmarkDistance();
            removeBenchmarkBar(client);
            client.gui.hud.getChat().addClientSystemMessage(Component.literal("FarAway benchmark failed: tile generation could not start."));
        }
    }

    private int preview(FabricClientCommandSource source) {
        return preview(source.getClient());
    }

    private int preview(Minecraft client) {
        if(!LodSettings.get().generationEnabled()) { feedback(client,session.get(),"FarAway tile generation is disabled in settings"); return 0; }
        var server=client.getSingleplayerServer();
        if(server==null || client.level==null || client.player==null || !client.level.dimension().equals(Level.OVERWORLD)) {
            feedback(client,session.get(),"Preview requires a vanilla singleplayer Overworld"); return 0;
        }
        long token=preview.reset(), worldSession=session.get();
        var anchor=TileKey.containing(client.player.blockPosition().getX(),client.player.blockPosition().getZ());
        feedback(client,worldSession,"Generating four preview tiles; streaming is not enabled");
        server.submit(() -> WorldDefinition.capture(server.overworld())).whenComplete((world,failure) -> {
            if(failure!=null) { feedback(client,worldSession,"Preview rejected: "+failure.getMessage()); return; }
            worker.execute(() -> {
                try {
                    java.util.function.BooleanSupplier cancelled=() -> preview.epoch()!=token || session.get()!=worldSession;
                    var sampler=new WorldTerrainSampler(world,new WorldTerrainSampler.Shared(world,cancelled));
                    var meshes=new java.util.ArrayList<dev.faraway.k7q4m9.mesh.TileMesh>();
                    var builder=new TerrainMeshBuilder();
                    long start=System.nanoTime();
                    for(int z=0;z<2;z++) for(int x=0;x<2;x++) {
                        var tile=LodTile.generate(new TileKey(anchor.x()+x,anchor.z()+z),sampler,cancelled);
                        meshes.add(builder.build(tile,cancelled));
                    }
                    if(!cancelled.getAsBoolean()) {
                        preview.publish(token,meshes);
                        feedback(client,worldSession,"Preview meshes ready: "+(System.nanoTime()-start)/1_000_000+" ms");
                    }
                } catch(java.util.concurrent.CancellationException cancelled) { /* superseded request */ }
                catch(Exception e) { FarAwayClient.LOGGER.error("Preview generation failed",e); feedback(client,worldSession,"Preview failed: "+e.getMessage()); }
            });
        });
        return 1;
    }

    /** Opt-in smoke run: no chat input or screenshots during measured intervals. */
    private void smokeTick(Minecraft client) {
        if(client.level==null || client.player==null || smokePhase==5) return;
        long now=System.nanoTime();
        if(smokePhase==0) {
            if(Boolean.getBoolean("faraway.referenceProfile")) client.getWindow().setWindowed(REFERENCE_WIDTH,REFERENCE_HEIGHT);
            if(Boolean.getBoolean("faraway.uncapped")) {
                client.options.enableVsync().set(false);
                client.options.framerateLimit().set(net.minecraft.client.Options.UNLIMITED_FRAMERATE_CUTOFF);
            }
            smokePhaseStart=now; smokePhase=1;
        }
        long seconds=(now-smokePhaseStart)/1_000_000_000L;
        if(smokePhase==1 && seconds>=5) {
            startCapture(client,now); smokePhaseStart=now; smokePhase=2;
            FarAwayClient.LOGGER.info("Smoke baseline capture started");
        } else if(smokePhase==2 && seconds>=20) {
            save(client,recorder.stop(),"smoke-baseline",null);
            if(Boolean.getBoolean("faraway.streamingSmoke")) {
                stream(client,TileKey.WORLD?6_000_000:256); startCapture(client,now);
            } else preview(client);
            smokePhaseStart=now; smokePhase=3;
        } else if(smokePhase==3 && (preview.status().startsWith("preview resident=") || preview.metrics().fullCoverage())) {
            if(recorder.active()) save(client,recorder.stop(),"smoke-streaming-cold",null);
            startCapture(client,now); smokePhaseStart=now; smokePhase=4;
            FarAwayClient.LOGGER.info("Smoke resident capture started: {}",preview.status());
        } else if(smokePhase==3 && seconds>=120) {
            if(recorder.active()) save(client,recorder.stop(),"smoke-streaming-timeout",null);
            smokePhase=5;
            FarAwayClient.LOGGER.error("Smoke preview timeout: {}",preview.status());
        } else if(smokePhase==4 && seconds>=20) {
            save(client,recorder.stop(),Boolean.getBoolean("faraway.streamingSmoke")?"smoke-streaming-resident":"smoke-resident",null); smokePhase=5;
            FarAwayClient.LOGGER.info("Smoke complete: {}",preview.status());
        }
    }

    private void feedback(Minecraft client, long token, String text) {
        client.execute(() -> {
            if (session.get() == token && client.player != null) client.gui.hud.getChat().addClientSystemMessage(Component.literal(text));
        });
    }

    private void startCapture(Minecraft client,long now) {
        activeCaptureId=System.currentTimeMillis()+"-"+java.util.UUID.randomUUID().toString().substring(0,8);
        captureStartProfile=profile(client);
        recorder.start(now); fpsRecorder.start(now); coverageRecorder.start(now);
    }
    private java.util.Map<String,String> settings(Minecraft client) {
        var settings=new java.util.HashMap<>(profile(client));
        settings.remove("position"); settings.remove("rotation"); settings.remove("clockStates"); settings.remove("weatherStrength");
        return java.util.Map.copyOf(settings);
    }
    private java.util.Map<String,String> profile(Minecraft client) {
        var values=new java.util.LinkedHashMap<String,String>();
        var options=client.options;
        values.put("minecraft","26.3");
        values.put("referenceWindow",REFERENCE_WIDTH+"x"+REFERENCE_HEIGHT);
        var build=dev.faraway.k7q4m9.diagnostics.BuildIdentity.CURRENT;
        values.put("farawayVersion",build.version());
        values.put("sourceSha256",build.sourceSha256());
        values.put("spacingBlocks",TileKey.WORLD?"dynamic:1..262144":"16");
        values.put("lodSamplePixels",Double.toString(LodSettings.get().samplePixels()));
        values.put("tileCells","32");
        values.put("tileBudget","4096");
        values.put("sampler","vanilla-26.3-early-surface-1");
        values.put("batchProbe",Boolean.toString(Boolean.getBoolean("faraway.batchProbe")));
        values.put("gpuBudgetBytes",Long.toString(dev.faraway.k7q4m9.client.LodSettings.toBytes(dev.faraway.k7q4m9.client.LodSettings.effectiveVramGiB())));
        values.put("farawayHeapBudgetBytes",Long.toString(dev.faraway.k7q4m9.client.LodSettings.toBytes(dev.faraway.k7q4m9.client.LodSettings.effectiveHeapGiB())));
        values.put("resultBudgetBytes",Long.toString(Math.max(512L*1024,Math.min(32L*1024*1024,dev.faraway.k7q4m9.client.LodSettings.toBytes(dev.faraway.k7q4m9.client.LodSettings.effectiveHeapGiB())/8))));
        values.put("uploadBudgetNs","1000000");
        values.put("uploadBudgetBytes","4194304");
        values.put("workerLimit",Integer.toString(LodSettings.get().workers()));
        values.put("effectiveFpsLimit",Integer.toString(client.getFramerateLimitTracker().getFramerateLimit()));
        values.put("fpsThrottleReason",client.getFramerateLimitTracker().getThrottleReason().toString());
        values.put("inactivityFpsLimit",options.inactivityFpsLimit().get().toString());
        values.put("java",System.getProperty("java.runtime.version"));
        values.put("window",client.getWindow().getWidth()+"x"+client.getWindow().getHeight());
        values.put("renderDistance",options.renderDistance().get().toString());
        values.put("effectiveRenderDistance",Integer.toString(options.getEffectiveRenderDistance()));
        values.put("simulationDistance",options.simulationDistance().get().toString());
        values.put("fpsLimit",options.framerateLimit().get().toString());
        values.put("vsync",options.enableVsync().get().toString());
        values.put("fov",options.fov().get().toString());
        values.put("graphics",options.graphicsPreset().get().toString());
        values.put("clouds",options.cloudStatus().get().toString());
        values.put("biomeBlend",options.biomeBlendRadius().get().toString());
        values.put("mipmaps",options.mipmapLevels().get().toString());
        values.put("maxHeap",Long.toString(Runtime.getRuntime().maxMemory()));
        if(client.level!=null) {
            values.put("dimension",client.level.dimension().identifier().toString());
            values.put("weatherStrength",client.level.getRainLevel(1)+","+client.level.getThunderLevel(1));
            var clocks=new java.util.TreeMap<String,String>();
            client.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK).listElements().forEach(holder -> {
                var clock=client.level.clockManager().getInstance(holder);
                clocks.put(holder.unwrapKey().orElseThrow().identifier().toString(),clock.totalTicks()+";rate="+clock.rate()+";paused="+clock.isPaused());
            });
            values.put("clockStates",clocks.toString());
        }
        if(client.player!=null) {
            values.put("position",client.player.position().toString());
            values.put("rotation",client.player.getYRot()+","+client.player.getXRot());
        }
        var mods=net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods().stream()
            .map(mod -> mod.getMetadata().getId()+"="+mod.getMetadata().getVersion().getFriendlyString()).sorted().toList();
        values.put("mods",mods.toString());
        return java.util.Map.copyOf(values);
    }

    private void save(Minecraft client, FrameRecorder.Capture capture, String label, FabricClientCommandSource source) {
        var fpsCapture = fpsRecorder.stop();
        save(client,capture,label,source,fpsCapture);
    }
    private void save(Minecraft client, FrameRecorder.Capture capture, String label, FabricClientCommandSource source,GameFpsRecorder.Capture fpsCapture) {
        var metrics=preview.metrics();
        var estimate=preview.workingSet();
        var coverage=coverageRecorder.stop();
        var startProfile=captureStartProfile;
        var captureId=activeCaptureId;
        var endProfile=profile(client);
        String window=client.getWindow().getWidth()+"x"+client.getWindow().getHeight();
        worker.execute(() -> {
            try {
                Path file = outputDirectory(client).resolve(label + "-" + captureId + ".csv");
                capture.writeCsv(file);
                fpsCapture.writeCsv(Path.of(file + ".fps.csv"));
                coverage.writeCsv(Path.of(file+".coverage.csv"));
                java.nio.file.Files.writeString(Path.of(file+".profile.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                    .toJson(java.util.Map.of("start",startProfile,"end",endProfile,"coverageDropped",coverage.dropped(),"captureId",captureId)));
                var summary = capture.summary();
                var fpsSummary = fpsCapture.summary();
                java.nio.file.Files.writeString(Path.of(file + ".txt"), "timestamp=" + Instant.now() + "\nlabel=" + label
                    + "\n" + fpsSummary + "\npreparation_ns=" + GameFpsRecorder.PREPARATION_NS
                    + "\nwindow="+window+"\nrenderer="+metrics+"\nworkingSetEstimate="+estimate
                    + "\nFrame timing (diagnostics only): " + summary + "\nScenario/coverage checks are still required for M1 acceptance.\n");
                FarAwayClient.LOGGER.info("Capture saved: {} FPS={} timing={}", file, fpsSummary, summary);
                if (source != null) client.execute(() -> source.sendFeedback(Component.literal(fpsSummary + "; " + file)));
            } catch (Exception e) { FarAwayClient.LOGGER.error("Capture export failed", e); }
        });
    }

    private static Path outputDirectory(Minecraft client) { return client.gameDirectory.toPath().resolve("faraway-benchmarks"); }
    private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) { return LiteralArgumentBuilder.literal(name); }
}
