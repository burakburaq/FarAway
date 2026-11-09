package dev.faraway.k7q4m9.client;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
/** Editable settings; changes are applied together after validation. */
public final class LodSettingsScreen extends Screen {
    private final SettingSlider[] sliders=new SettingSlider[18];
    private String error="";
    private static final double[] DISTANCE_OPTIONS={32,64,96,128,256,512,1024,2048,8192,32768,1048576,5303300};
    private EditBox customDistanceField,lowerField,upperField,planRateField,sampleSpacingField;
    private int sampleSpacing;
    private boolean asyncPlanning;
    private Button asyncButton,adaptiveButton;
    private boolean adaptiveLods;
    private EditBox planeThresholdField,hiZPollRateField;
    private boolean syncingDistance;
    private boolean opaqueLeaves,hiZOcclusion,hiZPaint,gpuBackfaceCulling,frustumCullingEdges,frustumCulling;
    private Button hiZButton,hiZPaintButton,backfaceButton,frustumEdgesButton,frustumCullingButton;
    private Button opaqueLeavesButton,anisotropicButton,randomizedTexturesButton;
    private boolean randomizedTextures;
    private int anisotropicFiltering;
    private LodSettings.MipmapMode mipmapMode;
    private Button mipmapButton,lockedOptionsButton,hideLockedOptionsButton;
    private boolean lockedOptions,hideLockedOptions,worldgenProfiling;
    private Button worldgenProfilingButton;
    private LodSettings.ScratchTerrain scratchTerrain;
    private boolean tileGenPool;
    private Button scratchTerrainButton,tileGenPoolButton;
    private boolean selectedSurface;
    private Button selectedSurfaceButton;
    private SettingSlider genPoolLimit,genPoolMemory;
    private boolean disableFog,clipDistance,enabled,voxelTerrain,wireframe,lodFog,colorByLod;
    private Button fogButton,clipButton,enabledButton,voxelButton,wireframeButton,lodFogButton,colorByLodButton;
    private boolean renderTiles,drawCalls;
    private Button renderTilesButton,drawCallsButton;
    private boolean performanceHud,tileDetailHud,advancedHud,hiZDetailsHud;
    private LodSettings.TriangleHudMode triangleHudMode;
    private Button advancedHudButton;
    private Button performanceHudButton,performanceButton,qualityButton;
    private Button triangleHudButton,tileDetailHudButton,hiZDetailsHudButton;
    private int performance;
    private boolean gpuSurfaceSampling,screenSpaceLod,packedQuadMesh,generatePlanes,generateVoxels,generateStructures,generateTrees;
    private Button packedQuadButton,generatePlanesButton,generateVoxelsButton,structuresButton,treesButton;
    private Button screenSpaceButton;
    private Button gpuSurfaceButton;
    private Button freecamButton,debugcamButton,applyButton,resetPageButton,cancelButton;
    private final java.util.Map<AbstractWidget,Integer> basicRows=new java.util.LinkedHashMap<>(),advancedRows=new java.util.LinkedHashMap<>(),debugRows=new java.util.LinkedHashMap<>();
    private final int[] pageScrollRows=new int[3];
    private static final int DEBUG_ROW_COUNT=42,ROW_STEP=24;
    private static final int DEBUG_OFF=0xFF00FF,DEBUG_MIDDLE=0x7F7FFF,DEBUG_ON=0x00FFFF;
    private Button previewButton,plannedLodsButton;
    private boolean showPlannedLods;
    private int previewMode;
    private int scatterMode;
    private boolean noiseEnabled;
    private Button noiseEnabledButton;
    private Button scatterModeButton;
    private int contentTop;
    private enum Tab { BASIC, ADVANCED, DEVTOOLS }
    private static Tab rememberedTab=Tab.BASIC;
    private Tab selected=rememberedTab;
    public static void resetRememberedTab() { rememberedTab=Tab.BASIC; }
    private final java.util.List<AbstractWidget> basicWidgets=new java.util.ArrayList<>(),advancedWidgets=new java.util.ArrayList<>(),debugWidgets=new java.util.ArrayList<>(),noiseWidgets=new java.util.ArrayList<>(),optimizationWidgets=new java.util.ArrayList<>(),experimentalWidgets=new java.util.ArrayList<>();
    private Button basicTab,advancedTab,debugTab;
    private final Screen parent;
    public LodSettingsScreen() { this(null); }
    public LodSettingsScreen(Screen parent) { super(Component.literal("FarAway Settings")); this.parent=parent; }
    @Override public void onClose() { if(parent!=null) minecraft.gui.setScreen(parent); else super.onClose(); }
    @Override protected void init() {
        var values=LodSettings.get();
        qualityButton=null;
        double heapMax=LodSettings.maxHeapGiB(),heapSliderMax=heapMax<1?heapMax:Math.floor(heapMax);
        double[] initial={values.samplePixels(),values.workers(),values.renderDistance(),values.heapGiB(),Math.min(4,values.vramGiB()),values.biomeBlendMaxLod(),values.minLodVisible(),values.maxLodVisible(),values.previewMode(),values.planeThreshold(),values.scatterStrength(),values.scatterScale(),values.scatterSeedOffset(),values.noiseBottom(),values.noiseTop(),values.lodLowerTolerance(),values.lodUpperTolerance(),values.adaptiveMaxLod()};
        double[] min={1,1,1,1,1,-1,0,0,0,1,0,1,-50,-100,-100,0,0,0}, max={4,Math.max(1,Runtime.getRuntime().availableProcessors()),6_000_000,Math.max(1,heapSliderMax)+1,4,18,18,18,3,100,100,20,50,100,100,90,100,18};
        String[] names={"Sample Spacing (px)","Worker Limit","Render Distance","RAM","VRAM","Biome Blend Distance","Nearest LOD","Farthest LOD","Preview","Plane Threshold","Scatter Strength","Scatter Scale","Scatter Offset","Noise Bottom","Noise Top","LOD Lower Tolerance (%)","LOD Upper Tolerance (%)","Adaptive Max LOD"};
        int left=width/2-155, top=Math.max(40,Math.min((height-208)/2,height-312)); contentTop=top;
        customDistanceField=null; planeThresholdField=null;lowerField=null;upperField=null;
        java.util.Arrays.fill(sliders,null);
        basicWidgets.clear(); advancedWidgets.clear(); debugWidgets.clear(); noiseWidgets.clear(); optimizationWidgets.clear(); experimentalWidgets.clear();
        basicTab=addRenderableWidget(Button.builder(Component.literal("Basic"),b -> selectTab(Tab.BASIC)).bounds(left,top,100,20).build());
        advancedTab=addRenderableWidget(Button.builder(Component.literal("Advanced"),b -> selectTab(Tab.ADVANCED)).bounds(left+105,top,100,20).build());
        debugTab=addRenderableWidget(Button.builder(Component.literal("DevTools"),b -> selectTab(Tab.DEVTOOLS)).bounds(left+210,top,100,20).build());
        for(int i=0;i<sliders.length;i++) {
            if(i==0 || i==8 || i==9) continue;
            int sliderX=(i==16 || i==7 || i==1 || i==2 || i==13 || i==14)?left+160:left;
            int sliderY=top+(i==2?164:i==17?140:i>=15?116:i==13?92:i==14?68:i>=10?68+(i-10)*24:i==5?92:i==7?68:i==1 || i==3 || i==6?44:68);
            sliders[i]=new SettingSlider(sliderX,sliderY,150,names[i],min[i],max[i],initial[i],i);
            if(i==5) sliders[i].setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Maximum LOD level with biome blending. Off disables blending.")));
            addRenderableWidget(sliders[i]);
            (i==17?advancedWidgets:i>=15?experimentalWidgets:i>=10?noiseWidgets:i==0 || i==1 || i==6 || i==7?debugWidgets:advancedWidgets).add(sliders[i]);
        }
        sampleSpacing=(int)values.samplePixels();
        sampleSpacingField=new EditBox(font,left+270,top+44,40,20,Component.literal("Sample Spacing (px)"));
        sampleSpacingField.setMaxLength(1);sampleSpacingField.setValue(Integer.toString(sampleSpacing));
        sampleSpacingField.setResponder(text -> {
            if(!text.matches("[1-4]?")) {sampleSpacingField.setValue(Integer.toString(sampleSpacing));return;}
            if(!text.isEmpty()) {sampleSpacing=Integer.parseInt(text);if(qualityButton!=null)updateGraphicsButton();}
        });
        addRenderableWidget(sampleSpacingField);debugWidgets.add(sampleSpacingField);
        lowerField=new EditBox(font,left,top+140,150,20,Component.literal("LOD Lower Tolerance (%)"));
        upperField=new EditBox(font,left+160,top+140,150,20,Component.literal("LOD Upper Tolerance (%)"));
        configureLinked(lowerField,15,values.lodLowerTolerance());configureLinked(upperField,16,values.lodUpperTolerance());
        experimentalWidgets.add(lowerField);experimentalWidgets.add(upperField);
        adaptiveLods=values.adaptiveLods();
        adaptiveButton=addRenderableWidget(Button.builder(adaptiveLabel(),b -> {adaptiveLods=!adaptiveLods;b.setMessage(adaptiveLabel());updateWidgetStates();}).bounds(left,top+140,150,20).build());
        adaptiveButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Retain adjacent LOD variants up to Adaptive Max LOD, after primary tiles are ready. Uses the selected VRAM limit.")));
        advancedWidgets.add(adaptiveButton);
        asyncPlanning=values.asyncLodPlanning();
        asyncButton=addRenderableWidget(Button.builder(asyncLabel(),b -> {asyncPlanning=!asyncPlanning;b.setMessage(asyncLabel());}).bounds(left,top+68,150,20).build());experimentalWidgets.add(asyncButton);
        planRateField=new EditBox(font,left+250,top+68,60,20,Component.literal("LOD Plan Rate (Hz)"));planRateField.setMaxLength(8);planRateField.setValue(Double.toString(values.lodPlanHz()));addRenderableWidget(planRateField);experimentalWidgets.add(planRateField);
        previewMode=values.previewMode();
        previewButton=addRenderableWidget(Button.builder(previewLabel(),b -> { previewMode=previewMode==0?2:previewMode==2?3:0; b.setMessage(previewLabel()); }).bounds(left,top+92,100,20).build());
        debugWidgets.add(previewButton);
        showPlannedLods=values.showPlannedLods();
        plannedLodsButton=addRenderableWidget(Button.builder(plannedLodsLabel(),b -> {showPlannedLods=!showPlannedLods;b.setMessage(plannedLodsLabel());}).bounds(left,top+116,150,20).build());
        debugWidgets.add(plannedLodsButton);
        planeThresholdField=new EditBox(font,left+260,top+44,50,20,Component.literal("Plane Threshold"));
        planeThresholdField.setMaxLength(3);
        planeThresholdField.setValue(Integer.toString(values.planeThreshold()));
        digitsOnly(planeThresholdField);
        addRenderableWidget(planeThresholdField); experimentalWidgets.add(planeThresholdField);
        noiseEnabled=values.noiseEnabled();
        noiseEnabledButton=addRenderableWidget(Button.builder(noiseEnabledLabel(),b -> {noiseEnabled=!noiseEnabled;b.setMessage(noiseEnabledLabel());updateWidgetStates();}).bounds(left,top+44,153,20).build());
        noiseWidgets.add(noiseEnabledButton);
        scatterMode=values.scatterMode();
        scatterModeButton=addRenderableWidget(Button.builder(scatterModeLabel(),b -> { scatterMode=1-scatterMode; b.setMessage(scatterModeLabel()); }).bounds(left+157,top+44,153,20).build());
        noiseWidgets.add(scatterModeButton);
        customDistanceField=new EditBox(font,left+250,top+44,60,20,Component.literal("Render Distance"));
        customDistanceField.setMaxLength(7);
        customDistanceField.setValue(Integer.toString(Math.max(32,Math.min(5_303_300,values.renderDistance()))));
        sliders[2].updateMessage();
        String[] lastDistance={customDistanceField.getValue()};
        customDistanceField.setResponder(value -> {
            if(!value.matches("[0-9]*")) { customDistanceField.setValue(lastDistance[0]); return; }
            lastDistance[0]=value;
            if(syncingDistance) return;
            try {
                int distance=Integer.parseInt(value);
                if(distance>=32 && distance<=5_303_300) {
                    syncingDistance=true; sliders[2].setNumber(distance); syncingDistance=false;
                    sliders[2].updateMessage();
                }
            } catch(NumberFormatException ignored) { }
        });
        addRenderableWidget(customDistanceField); debugWidgets.add(customDistanceField);
        disableFog=values.disableDistanceFog(); clipDistance=values.clipDistance(); enabled=values.enabled(); voxelTerrain=values.voxelTerrain(); wireframe=values.wireframe(); lodFog=values.lodDistanceFog(); colorByLod=values.colorByLod();
        enabledButton=addRenderableWidget(Button.builder(enabledLabel(), b -> { enabled=!enabled; b.setMessage(enabledLabel()); }).bounds(left,top+44,150,20).build());
        voxelButton=addRenderableWidget(Button.builder(voxelLabel(), b -> { voxelTerrain=!voxelTerrain; b.setMessage(voxelLabel()); }).bounds(left,top+44,150,20).build());
        clipButton=addRenderableWidget(Button.builder(clipLabel(), b -> { clipDistance=!clipDistance; b.setMessage(clipLabel()); }).bounds(left+160,top+116,150,20).build());
        performance=values.performance(); performanceHud=values.performanceHud();
        performanceHudButton=addRenderableWidget(Button.builder(performanceHudLabel(), b -> { performanceHud=!performanceHud; b.setMessage(performanceHudLabel()); }).bounds(left,top+92,150,20).build());
        performanceButton=addRenderableWidget(Button.builder(performanceLabel(),b -> { performance=(performance+1)%4; updateEffortButton(); }).bounds(left+160,top+44,150,20).build());
        qualityButton=addRenderableWidget(Button.builder(qualityLabel(),b -> {setSampleSpacing(sampleSpacing==1?4:sampleSpacing-1);}).bounds(left,top+68,310,20).build());
        updateGraphicsButton();updateEffortButton();
        enabledButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Create new LOD tiles in the background. Off pauses generation while keeping existing tiles available.")));
        basicWidgets.add(qualityButton);
        basicWidgets.addAll(java.util.List.of(enabledButton,performanceButton));
        debugWidgets.add(performanceHudButton);
        renderTiles=values.renderTiles();drawCalls=values.drawCalls();
        renderTilesButton=addRenderableWidget(Button.builder(renderTilesLabel(),b -> {renderTiles=!renderTiles;b.setMessage(renderTilesLabel());}).bounds(left,top+140,150,20).build());basicWidgets.add(renderTilesButton);
        renderTilesButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Display FarAway terrain and water. Off hides LOD tiles while background generation can continue.")));
        drawCallsButton=addRenderableWidget(Button.builder(drawCallsLabel(),b -> {drawCalls=!drawCalls;b.setMessage(drawCallsLabel());}).bounds(left,top+164,150,20).build());experimentalWidgets.add(drawCallsButton);
        advancedHud=values.advancedHud();
        advancedHudButton=addRenderableWidget(Button.builder(advancedHudLabel(),b -> {advancedHud=!advancedHud;b.setMessage(advancedHudLabel());}).bounds(left,top+188,150,20).build());debugWidgets.add(advancedHudButton);
        triangleHudMode=values.triangleHudMode();
        tileDetailHud=values.tileDetailHud();
        hiZDetailsHud=values.hiZDetailsHud();
        triangleHudButton=addRenderableWidget(Button.builder(triangleHudLabel(),b -> {
            var modes=LodSettings.TriangleHudMode.values();
            triangleHudMode=modes[(triangleHudMode.ordinal()+1)%modes.length];
            b.setMessage(triangleHudLabel());
        }).bounds(left,top,150,20).build());
        tileDetailHudButton=addRenderableWidget(Button.builder(tileDetailHudLabel(),b -> {
            tileDetailHud=!tileDetailHud;b.setMessage(tileDetailHudLabel());
        }).bounds(left,top,150,20).build());
        hiZDetailsHudButton=addRenderableWidget(Button.builder(hiZDetailsHudLabel(),b -> {
            hiZDetailsHud=!hiZDetailsHud;b.setMessage(hiZDetailsHudLabel());
        }).bounds(left,top,150,20).build());
        debugWidgets.addAll(java.util.List.of(triangleHudButton,tileDetailHudButton,hiZDetailsHudButton));
        fogButton=addRenderableWidget(Button.builder(fogLabel(), b -> { disableFog=!disableFog; b.setMessage(fogLabel()); }).bounds(left+160,top+140,150,20).build());
        fogButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("On disables vanilla distance fog. Off restores vanilla distance fog.")));
        lodFogButton=addRenderableWidget(Button.builder(lodFogLabel(), b -> { lodFog=!lodFog; b.setMessage(lodFogLabel()); }).bounds(left,top+116,150,20).build());
        advancedWidgets.add(lodFogButton);debugWidgets.add(clipButton);
        colorByLodButton=addRenderableWidget(Button.builder(colorByLodLabel(), b -> { colorByLod=!colorByLod; b.setMessage(colorByLodLabel()); }).bounds(left+105,top+92,100,20).build());
        wireframeButton=addRenderableWidget(Button.builder(wireframeLabel(), b -> { wireframe=!wireframe; b.setMessage(wireframeLabel()); }).bounds(left+210,top+92,100,20).build());
        gpuSurfaceSampling=values.gpuSurfaceSampling();
        gpuSurfaceButton=addRenderableWidget(Button.builder(gpuSurfaceLabel(),b -> { gpuSurfaceSampling=!gpuSurfaceSampling; b.setMessage(gpuSurfaceLabel()); }).bounds(left+160,top+44,150,20).build());
        freecamButton=addRenderableWidget(Button.builder(freecamLabel(),b -> { Freecam.toggleFreecam(); updateCameraLabels(); }).bounds(left+160,top+92,150,20).build());
        debugWidgets.add(freecamButton);
        debugcamButton=addRenderableWidget(Button.builder(debugcamLabel(),b -> { Freecam.toggle(); updateCameraLabels(); }).bounds(left,top+140,150,20).build());
        debugcamButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Detached camera. LOD generation and culling use the frozen player camera.")));
        freecamButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Detached camera. LOD generation and culling follow this camera.")));
        debugWidgets.add(debugcamButton);
        gpuSurfaceButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Experimental approximate terrain at LOD 1+. Heights differ from vanilla and structures may not align. Requires OpenGL compute support; falls back to CPU.")));
        experimentalWidgets.add(gpuSurfaceButton);
        opaqueLeaves=values.opaqueLeaves();
        opaqueLeavesButton=addRenderableWidget(Button.builder(opaqueLeavesLabel(),b -> {opaqueLeaves=!opaqueLeaves;b.setMessage(opaqueLeavesLabel());}).bounds(left+160,top+68,150,20).build());
        advancedWidgets.add(opaqueLeavesButton);
        mipmapMode=values.mipmapMode();
        mipmapButton=addRenderableWidget(Button.builder(mipmapLabel(),button -> {
            mipmapMode=mipmapMode.next();
            button.setMessage(mipmapLabel());
        }).bounds(left,top,150,20).build());
        mipmapButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Global applies to LODs only while Locked Options is off.")));
        lockedOptions=values.lockedOptions();
        hideLockedOptions=values.hideLockedOptions();
        lockedOptionsButton=addRenderableWidget(Button.builder(lockedOptionsLabel(),button -> {
            lockedOptions=!lockedOptions;
            button.setMessage(lockedOptionsLabel());
        }).bounds(left,top,150,20).build());
        hideLockedOptionsButton=addRenderableWidget(Button.builder(hideLockedOptionsLabel(),button -> {
            hideLockedOptions=!hideLockedOptions;
            button.setMessage(hideLockedOptionsLabel());
        }).bounds(left+160,top,150,20).build());
        worldgenProfiling=values.worldgenProfiling();
        worldgenProfilingButton=addRenderableWidget(Button.builder(worldgenProfilingLabel(),button -> {
            worldgenProfiling=!worldgenProfiling;
            button.setMessage(worldgenProfilingLabel());
        }).bounds(left+160,top,150,20).build());
        worldgenProfilingButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
            Component.literal("Capture vanilla chunk and FarAway tile timings. Turn off to write a session report in logs.")));
        experimentalWidgets.addAll(java.util.List.of(mipmapButton,lockedOptionsButton,hideLockedOptionsButton,worldgenProfilingButton));
        var generation=values.generation();
        scratchTerrain=generation.scratchTerrain();
        tileGenPool=generation.poolEnabled();
        selectedSurface=generation.selectedSurface();
        selectedSurfaceButton=addRenderableWidget(Button.builder(selectedSurfaceLabel(),button -> {
            selectedSurface=!selectedSurface;
            button.setMessage(selectedSurfaceLabel());
        }).bounds(left,top,310,20).build());
        selectedSurfaceButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
            Component.literal("Speeds up new LOD 2 tiles in stock Overworld worlds. Some surface colors and materials may differ.")));
        scratchTerrainButton=addRenderableWidget(Button.builder(scratchTerrainLabel(),button -> {
            var modes=LodSettings.ScratchTerrain.values();
            scratchTerrain=modes[(scratchTerrain.ordinal()+1)%modes.length];
            button.setMessage(scratchTerrainLabel());
        }).bounds(left,top,310,20).build());
        tileGenPoolButton=addRenderableWidget(Button.builder(tileGenPoolLabel(),button -> {
            tileGenPool=!tileGenPool;
            button.setMessage(tileGenPoolLabel());
            updateWidgetStates();
        }).bounds(left,top,310,20).build());
        genPoolLimit=addRenderableWidget(new SettingSlider(left,top,150,"Gen. Pool Limit",1,8,generation.poolMultiplier(),18));
        genPoolMemory=addRenderableWidget(new SettingSlider(left+160,top,150,"Gen. Pool Memory",0,512,generation.poolMiB(),19));
        optimizationWidgets.add(scratchTerrainButton);
        experimentalWidgets.addAll(java.util.List.of(selectedSurfaceButton,tileGenPoolButton,genPoolLimit,genPoolMemory));
        anisotropicFiltering=values.anisotropicFiltering();
        anisotropicButton=addRenderableWidget(Button.builder(anisotropicLabel(),b -> {anisotropicFiltering=anisotropicFiltering==16?1:anisotropicFiltering*2;b.setMessage(anisotropicLabel());}).bounds(left,top,310,20).build());
        anisotropicButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Texture filtering for voxel blocks and water. Off, 2x, 4x, 8x or 16x, limited by your GPU. Mipmap behavior is set under DevTools.")));
        randomizedTextures=values.randomizedTextures();
        randomizedTexturesButton=addRenderableWidget(Button.builder(randomizedTexturesLabel(),b -> {randomizedTextures=!randomizedTextures;b.setMessage(randomizedTexturesLabel());}).bounds(left+160,top,150,20).build());
        randomizedTexturesButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Rotate and mirror eligible block textures using their Minecraft model variants. Each block keeps a consistent choice.")));
        advancedWidgets.addAll(java.util.List.of(anisotropicButton,randomizedTexturesButton));
        frustumCullingEdges=values.frustumCullingEdges();
        frustumEdgesButton=addRenderableWidget(Button.builder(frustumEdgesLabel(),b -> {frustumCullingEdges=!frustumCullingEdges;b.setMessage(frustumEdgesLabel());}).bounds(left+160,top+116,150,20).build());
        frustumEdgesButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Yellow outlines follow the lowest terrain edges at the left/right culling boundaries, with sea level as the minimum height. DebugCam uses the frozen player view. Independent of Hi-Z.")));
        debugWidgets.add(frustumEdgesButton);
        hiZOcclusion=values.hiZOcclusion();
        hiZButton=addRenderableWidget(Button.builder(hiZLabel(),b -> {hiZOcclusion=!hiZOcclusion;b.setMessage(hiZLabel());}).bounds(left,top+44,150,20).build());
        hiZButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("GPU Hi-Z occlusion culling. Current-frame depth; HUD totals are read asynchronously. Requires compute and indirect-draw support; otherwise normal drawing is retained.")));
        experimentalWidgets.add(hiZButton);
        hiZPollRateField=new EditBox(font,left+110,top+68,40,20,Component.literal("Hi-Z Data Poll Rate"));
        hiZPollRateField.setMaxLength(10);hiZPollRateField.setValue(Integer.toString(values.hiZDataPollRate()));digitsOnly(hiZPollRateField);
        hiZPollRateField.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Positive integer Hz. Controls asynchronous HUD totals only; visibility is tested every frame. Results never wait for the GPU.")));
        addRenderableWidget(hiZPollRateField);experimentalWidgets.add(hiZPollRateField);
        hiZPaint=values.hiZPaint();
        hiZPaintButton=addRenderableWidget(Button.builder(hiZPaintLabel(),b -> {hiZPaint=!hiZPaint;b.setMessage(hiZPaintLabel());}).bounds(left,top+116,150,20).build());
        hiZPaintButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Requires Hi-Z Culling. Culled tiles are drawn pure red. HUD still reports normal culling. Observe from DebugCam.")));
        debugWidgets.add(hiZPaintButton);
        gpuBackfaceCulling=values.gpuBackfaceCulling();
        backfaceButton=addRenderableWidget(Button.builder(backfaceLabel(),b -> {gpuBackfaceCulling=!gpuBackfaceCulling;b.setMessage(backfaceLabel());}).bounds(left+160,top+92,150,20).build());
        backfaceButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("GPU backface culling for voxel terrain and water. Off also disables CPU face selection so back faces reach the GPU. Applies without regeneration.")));
        optimizationWidgets.add(backfaceButton);
        frustumCulling=values.frustumCulling();
        frustumCullingButton=addRenderableWidget(Button.builder(frustumCullingLabel(),b -> {
            frustumCulling=!frustumCulling;b.setMessage(frustumCullingLabel());
        }).bounds(left,top,150,20).build());
        frustumCullingButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
            Component.literal("Skip FarAway tiles outside the camera view. Applies without tile regeneration.")));
        optimizationWidgets.add(frustumCullingButton);
        experimentalWidgets.add(voxelButton);
        screenSpaceLod=values.screenSpaceLod();
        screenSpaceButton=addRenderableWidget(Button.builder(screenSpaceLabel(),b -> {screenSpaceLod=!screenSpaceLod;b.setMessage(screenSpaceLabel());}).bounds(left,top+68,150,20).build());
        experimentalWidgets.add(screenSpaceButton);
        packedQuadMesh=values.packedQuadMesh();
        packedQuadButton=addRenderableWidget(Button.builder(packedQuadLabel(),b -> {packedQuadMesh=!packedQuadMesh;b.setMessage(packedQuadLabel());}).bounds(left+160,top+68,150,20).build());
        experimentalWidgets.add(packedQuadButton);
        generatePlanes=values.generatePlaneTiles();generateVoxels=values.generateVoxelTiles();
        generateStructures=values.generateStructures();generateTrees=values.generateTrees();
        generatePlanesButton=addRenderableWidget(Button.builder(generatePlanesLabel(),b -> {generatePlanes=!generatePlanes;b.setMessage(generatePlanesLabel());updateWidgetStates();}).bounds(left,top,150,20).build());
        generateVoxelsButton=addRenderableWidget(Button.builder(generateVoxelsLabel(),b -> {generateVoxels=!generateVoxels;b.setMessage(generateVoxelsLabel());updateWidgetStates();}).bounds(left,top,150,20).build());
        structuresButton=addRenderableWidget(Button.builder(structuresLabel(),b -> {generateStructures=!generateStructures;b.setMessage(structuresLabel());}).bounds(left,top,150,20).build());
        treesButton=addRenderableWidget(Button.builder(treesLabel(),b -> {generateTrees=!generateTrees;b.setMessage(treesLabel());}).bounds(left,top,150,20).build());
        structuresButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Generate supported surface structures. Off skips structure discovery and placement.")));
        treesButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Generate natural trees and forest canopy colors on plane tiles.")));
        generatePlanesButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Allow plane tiles. With voxel generation off, all tiles become planes. Both off stops new tile generation.")));
        generateVoxelsButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Allow voxel tiles. With plane generation off, all tiles become voxels. Existing tiles remain until replacements are ready.")));
        debugWidgets.addAll(java.util.List.of(generatePlanesButton,generateVoxelsButton,structuresButton,treesButton));
        screenSpaceButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Select LOD from four sea-level corner footprints. Rotation updates the plan; old tiles remain until replacements are ready.")));
        sliders[4].setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("GPU memory budget in GiB. Idle tiles are retained until the selected budget requires eviction. Adaptive LOD is controlled separately.")));
        debugWidgets.addAll(java.util.List.of(colorByLodButton,wireframeButton,fogButton));
        resetPageButton=addRenderableWidget(Button.builder(Component.literal("Reset Page"), button -> resetPage()).bounds(left,top+212,100,20).build());
        applyButton=addRenderableWidget(Button.builder(Component.literal("Apply"), button -> save()).bounds(left+105,top+212,100,20).build());
        cancelButton=addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose()).bounds(left+210,top+212,100,20).build());
        debugWidgets.addAll(optimizationWidgets);debugWidgets.addAll(experimentalWidgets);debugWidgets.addAll(noiseWidgets);
        configureBasicLayout(left);
        configureAdvancedLayout(left);
        configureDebugLayout(left);
        selectTab(selected);
        updateWidgetStates();
    }
    private void selectTab(Tab tab) {
        selected=tab; rememberedTab=tab; setFocused(null);
        for(var widget:basicWidgets) widget.visible=tab==Tab.BASIC;
        for(var widget:advancedWidgets) widget.visible=tab==Tab.ADVANCED;
        for(var widget:debugWidgets) widget.visible=tab==Tab.DEVTOOLS;
        basicTab.active=tab!=Tab.BASIC;advancedTab.active=tab!=Tab.ADVANCED;debugTab.active=tab!=Tab.DEVTOOLS;
        layoutPage();
    }
    private void configureBasicLayout(int left) {
        basicRows.clear();
        pageControl(basicRows,enabledButton,left,0,150);pageControl(basicRows,performanceButton,left+160,0,150);
        pageControl(basicRows,renderTilesButton,left,1,150);pageControl(basicRows,qualityButton,left+160,1,150);
    }
    private void configureAdvancedLayout(int left) {
        advancedRows.clear();
        pageControl(advancedRows,sliders[3],left,1,150);pageControl(advancedRows,sliders[4],left+160,1,150);
        pageControl(advancedRows,sliders[2],left,3,150);pageControl(advancedRows,sliders[5],left+160,3,150);
        pageControl(advancedRows,adaptiveButton,left,4,150);pageControl(advancedRows,sliders[17],left+160,4,150);
        pageControl(advancedRows,lodFogButton,left,6,150);pageControl(advancedRows,opaqueLeavesButton,left+160,6,150);
        pageControl(advancedRows,anisotropicButton,left,7,150);pageControl(advancedRows,randomizedTexturesButton,left+160,7,150);
    }
    private void configureDebugLayout(int left) {
        debugRows.clear();
        debugControl(structuresButton,left,1,150);debugControl(treesButton,left+160,1,150);
        debugControl(generateVoxelsButton,left,2,150);debugControl(generatePlanesButton,left+160,2,150);
        debugControl(previewButton,left,4,150);debugControl(frustumEdgesButton,left+160,4,150);
        debugControl(wireframeButton,left,5,150);debugControl(plannedLodsButton,left+160,5,150);
        debugControl(hiZPaintButton,left,7,150);debugControl(colorByLodButton,left+160,7,150);
        debugControl(freecamButton,left,9,150);debugControl(debugcamButton,left+160,9,150);
        debugControl(performanceHudButton,left,11,150);debugControl(triangleHudButton,left+160,11,150);
        debugControl(tileDetailHudButton,left,12,150);debugControl(advancedHudButton,left+160,12,150);
        debugControl(hiZDetailsHudButton,left,13,150);
        debugControl(sliders[6],left,15,150);debugControl(sliders[7],left+160,15,150);
        debugControl(customDistanceField,left+100,16,50);debugControl(sampleSpacingField,left+270,16,40);
        debugControl(sliders[1],left,18,310);
        debugControl(fogButton,left,19,150);
        debugControl(backfaceButton,left,21,150);debugControl(clipButton,left+160,21,150);
        debugControl(scratchTerrainButton,left,22,310);
        debugControl(frustumCullingButton,left,23,150);
        debugControl(noiseEnabledButton,left,25,150);debugControl(scatterModeButton,left+160,25,150);
        debugControl(sliders[10],left,26,150);debugControl(sliders[14],left+160,26,150);
        debugControl(sliders[11],left,27,150);debugControl(sliders[13],left+160,27,150);
        debugControl(sliders[12],left,28,150);
        debugControl(lockedOptionsButton,left,30,150);debugControl(hideLockedOptionsButton,left+160,30,150);
        debugControl(voxelButton,left,31,150);debugControl(gpuSurfaceButton,left+160,31,150);
        debugControl(screenSpaceButton,left,32,150);debugControl(packedQuadButton,left+160,32,150);
        debugControl(mipmapButton,left,33,150);debugControl(drawCallsButton,left+160,33,150);
        debugControl(hiZButton,left,34,150);debugControl(hiZPollRateField,left+270,34,40);
        debugControl(asyncButton,left,35,150);debugControl(planRateField,left+260,35,50);
        debugControl(sliders[15],left,36,150);debugControl(sliders[16],left+160,36,150);
        debugControl(lowerField,left,37,150);debugControl(upperField,left+160,37,150);
        debugControl(planeThresholdField,left+100,38,50);debugControl(worldgenProfilingButton,left+160,38,150);
        debugControl(selectedSurfaceButton,left,39,310);
        debugControl(tileGenPoolButton,left,40,310);
        debugControl(genPoolLimit,left,41,150);debugControl(genPoolMemory,left+160,41,150);
    }
    private void debugControl(AbstractWidget widget,int x,int row,int width) { pageControl(debugRows,widget,x,row,width); }
    private void pageControl(java.util.Map<AbstractWidget,Integer> rows,AbstractWidget widget,int x,int row,int width) {
        widget.setX(x);widget.setWidth(width);rows.put(widget,row);
    }
    private java.util.Map<AbstractWidget,Integer> pageRows(Tab tab) {
        return switch(tab){case BASIC -> basicRows;case ADVANCED -> advancedRows;case DEVTOOLS -> debugRows;};
    }
    private int pageRowCount(Tab tab) {return switch(tab){case BASIC -> 2;case ADVANCED -> 8;case DEVTOOLS -> DEBUG_ROW_COUNT;};}
    private int pageFooterY(Tab tab) {return Math.min(contentTop+24+Math.min(10,pageRowCount(tab))*ROW_STEP,height-24);}
    private int visiblePageRows(Tab tab) {return Math.max(1,(pageFooterY(tab)-(contentTop+24))/ROW_STEP);}
    private boolean rowVisible(int row) {return row>=pageScrollRows[selected.ordinal()] && row<pageScrollRows[selected.ordinal()]+visiblePageRows(selected);}
    private int rowY(int row) {return contentTop+24+(row-pageScrollRows[selected.ordinal()])*ROW_STEP;}
    private void layoutPage() {
        for(var tab:Tab.values()) {
            int visible=visiblePageRows(tab),maxScroll=Math.max(0,pageRowCount(tab)-visible);
            int scroll=pageScrollRows[tab.ordinal()]=Math.max(0,Math.min(maxScroll,pageScrollRows[tab.ordinal()]));
            for(var entry:pageRows(tab).entrySet()) {
                var widget=entry.getKey();int row=entry.getValue();
                widget.setY(contentTop+24+(row-scroll)*ROW_STEP);
                widget.visible=selected==tab && row>=scroll && row<scroll+visible;
                if(!widget.visible && getFocused()==widget)setFocused(null);
            }
        }
        int footer=pageFooterY(selected);
        resetPageButton.setY(footer);applyButton.setY(footer);cancelButton.setY(footer);
    }
    @Override public boolean mouseScrolled(double mouseX,double mouseY,double horizontal,double vertical) {
        int left=width/2-155;
        if(vertical!=0 && pageRowCount(selected)>visiblePageRows(selected) && mouseX>=left && mouseX<=left+320 && mouseY>=contentTop+24 && mouseY<pageFooterY(selected)) {
            pageScrollRows[selected.ordinal()]-=vertical>0?1:-1;layoutPage();return true;
        }
        return super.mouseScrolled(mouseX,mouseY,horizontal,vertical);
    }
    private void pageText(GuiGraphicsExtractor graphics,String text,int column,int row) {
        if(rowVisible(row))graphics.text(font,text,width/2-155+column*160,rowY(row)+6,0xffffffff,true);
    }
    private void pageHeading(GuiGraphicsExtractor graphics,String text,int row) {
        if(rowVisible(row))graphics.centeredText(font,text,width/2,rowY(row)+6,0xffffffff);
    }
    private void renderAdvancedLabels(GuiGraphicsExtractor graphics) {
        pageHeading(graphics,"Resources",0);pageHeading(graphics,"LOD",2);pageHeading(graphics,"Rendering",5);
    }
    private void renderDevToolsLabels(GuiGraphicsExtractor graphics) {
        pageHeading(graphics,"Tile Details",0);
        pageHeading(graphics,"Debug Lines",3);
        pageHeading(graphics,"Paint Tiles",6);
        pageHeading(graphics,"Utility",8);
        pageHeading(graphics,"HUD Details",10);
        pageHeading(graphics,"Tile Rendering",14);
        pageHeading(graphics,"Other",17);
        pageHeading(graphics,"Optimization",20);
        pageHeading(graphics,"Noise",24);
        pageHeading(graphics,"Experimental",29);
        pageText(graphics,"Render Distance",0,16);
        pageText(graphics,"Sample Spacing (px)",1,16);
        pageText(graphics,"Hi-Z Data Poll Rate",1,34);
        pageText(graphics,"LOD Plan Rate (Hz)",1,35);
        pageText(graphics,"Plane Threshold",0,38);
    }
    private void renderPageScrollbar(GuiGraphicsExtractor graphics) {
        int visible=visiblePageRows(selected),total=pageRowCount(selected);
        if(visible<total) {
            int x=width/2+159,y=contentTop+24,track=visible*ROW_STEP-4;
            int thumb=Math.max(12,track*visible/total);
            int offset=(track-thumb)*pageScrollRows[selected.ordinal()]/(total-visible);
            graphics.fill(x,y,x+3,y+track,0xff444444);
            graphics.fill(x,y+offset,x+3,y+offset+thumb,0xffaaaaaa);
        }
    }
    private void resetPage() {
        var d=LodSettings.defaults();
        switch(selected) {
            case BASIC -> {
                renderTiles=d.renderTiles();renderTilesButton.setMessage(renderTilesLabel());
                enabled=d.enabled(); enabledButton.setMessage(enabledLabel());
                performance=d.performance(); updateEffortButton();
                setSampleSpacing((int)d.samplePixels());
            }
            case ADVANCED -> {
                sliders[2].setNumber(d.renderDistance());customDistanceField.setValue(Integer.toString(d.renderDistance()));
                sliders[17].setNumber(d.adaptiveMaxLod());
                adaptiveLods=d.adaptiveLods();adaptiveButton.setMessage(adaptiveLabel());
                sliders[3].setNumber(d.heapGiB()); sliders[4].setNumber(d.vramGiB());
                sliders[5].setNumber(d.biomeBlendMaxLod());
                randomizedTextures=d.randomizedTextures();randomizedTexturesButton.setMessage(randomizedTexturesLabel());
                anisotropicFiltering=d.anisotropicFiltering();anisotropicButton.setMessage(anisotropicLabel());
                opaqueLeaves=d.opaqueLeaves();opaqueLeavesButton.setMessage(opaqueLeavesLabel());
                lodFog=d.lodDistanceFog(); lodFogButton.setMessage(lodFogLabel());
            }

            case DEVTOOLS -> {
                performanceHud=d.performanceHud();performanceHudButton.setMessage(performanceHudLabel());
                triangleHudMode=d.triangleHudMode();triangleHudButton.setMessage(triangleHudLabel());
                tileDetailHud=d.tileDetailHud();tileDetailHudButton.setMessage(tileDetailHudLabel());
                hiZDetailsHud=d.hiZDetailsHud();hiZDetailsHudButton.setMessage(hiZDetailsHudLabel());
                setSampleSpacing((int)d.samplePixels());
                drawCalls=d.drawCalls();drawCallsButton.setMessage(drawCallsLabel());
                advancedHud=d.advancedHud();advancedHudButton.setMessage(advancedHudLabel());
                clipDistance=d.clipDistance();clipButton.setMessage(clipLabel());
                mipmapMode=d.mipmapMode();mipmapButton.setMessage(mipmapLabel());
                lockedOptions=d.lockedOptions();lockedOptionsButton.setMessage(lockedOptionsLabel());
                hideLockedOptions=d.hideLockedOptions();hideLockedOptionsButton.setMessage(hideLockedOptionsLabel());
                worldgenProfiling=d.worldgenProfiling();worldgenProfilingButton.setMessage(worldgenProfilingLabel());
                scratchTerrain=d.generation().scratchTerrain();scratchTerrainButton.setMessage(scratchTerrainLabel());
                selectedSurface=d.generation().selectedSurface();selectedSurfaceButton.setMessage(selectedSurfaceLabel());
                tileGenPool=d.generation().poolEnabled();tileGenPoolButton.setMessage(tileGenPoolLabel());
                genPoolLimit.setNumber(d.generation().poolMultiplier());genPoolMemory.setNumber(d.generation().poolMiB());
                sliders[1].setNumber(d.workers());
                hiZPollRateField.setValue(Integer.toString(d.hiZDataPollRate()));
                customDistanceField.setValue(Integer.toString(d.renderDistance()));
                if(Freecam.active()) Freecam.disable(); updateCameraLabels();
                sliders[6].setNumber(d.minLodVisible()); sliders[7].setNumber(d.maxLodVisible());
                previewMode=d.previewMode(); previewButton.setMessage(previewLabel());
                showPlannedLods=d.showPlannedLods();plannedLodsButton.setMessage(plannedLodsLabel());
                colorByLod=d.colorByLod(); colorByLodButton.setMessage(colorByLodLabel());
                wireframe=d.wireframe(); wireframeButton.setMessage(wireframeLabel());
                hiZPaint=d.hiZPaint();hiZPaintButton.setMessage(hiZPaintLabel());
                frustumCullingEdges=d.frustumCullingEdges();frustumEdgesButton.setMessage(frustumEdgesLabel());
                disableFog=d.disableDistanceFog(); fogButton.setMessage(fogLabel());


                lowerField.setValue(Integer.toString(d.lodLowerTolerance()));upperField.setValue(Integer.toString(d.lodUpperTolerance()));planRateField.setValue(Double.toString(d.lodPlanHz()));asyncPlanning=d.asyncLodPlanning();asyncButton.setMessage(asyncLabel());
                planeThresholdField.setValue(Integer.toString(d.planeThreshold()));
                hiZOcclusion=d.hiZOcclusion();hiZButton.setMessage(hiZLabel());
                gpuBackfaceCulling=d.gpuBackfaceCulling();backfaceButton.setMessage(backfaceLabel());
                frustumCulling=d.frustumCulling();frustumCullingButton.setMessage(frustumCullingLabel());
                generatePlanes=d.generatePlaneTiles();generatePlanesButton.setMessage(generatePlanesLabel());
                generateVoxels=d.generateVoxelTiles();generateVoxelsButton.setMessage(generateVoxelsLabel());
                generateStructures=d.generateStructures();structuresButton.setMessage(structuresLabel());
                generateTrees=d.generateTrees();treesButton.setMessage(treesLabel()); packedQuadMesh=d.packedQuadMesh();packedQuadButton.setMessage(packedQuadLabel()); screenSpaceLod=d.screenSpaceLod();screenSpaceButton.setMessage(screenSpaceLabel()); voxelTerrain=d.voxelTerrain(); voxelButton.setMessage(voxelLabel()); gpuSurfaceSampling=d.gpuSurfaceSampling(); gpuSurfaceButton.setMessage(gpuSurfaceLabel());
                int[] values={d.scatterStrength(),d.scatterScale(),d.scatterSeedOffset()};
                for(int i=0;i<3;i++) sliders[i+10].setNumber(values[i]);
                sliders[13].setNumber(d.noiseBottom()); sliders[14].setNumber(d.noiseTop());
                noiseEnabled=d.noiseEnabled();noiseEnabledButton.setMessage(noiseEnabledLabel());
                scatterMode=d.scatterMode(); scatterModeButton.setMessage(scatterModeLabel());

            }


        }
        error="";
        updateWidgetStates();
    }
    private boolean resetAt(net.minecraft.client.input.MouseButtonEvent event) {
        if(event.button()!=1 || !event.hasShiftDown()) return false;
        var d=LodSettings.defaults();
        double[] defaults={d.samplePixels(),d.workers(),d.renderDistance(),d.heapGiB(),d.vramGiB(),d.biomeBlendMaxLod(),d.minLodVisible(),d.maxLodVisible(),d.previewMode(),d.planeThreshold(),d.scatterStrength(),d.scatterScale(),d.scatterSeedOffset(),d.noiseBottom(),d.noiseTop(),d.lodLowerTolerance(),d.lodUpperTolerance(),d.adaptiveMaxLod()};
        for(int i=0;i<sliders.length;i++) if(sliders[i]!=null && sliders[i].visible && sliders[i].active && sliders[i].isMouseOver(event.x(),event.y())) {
            sliders[i].setNumber(defaults[i]);
            if(i==2) customDistanceField.setValue(Integer.toString((int)defaults[i]));
            error=""; return true;
        }
        for(var pair:java.util.List.of(lowerField,upperField,planRateField)) if(pair.visible && pair.active && pair.isMouseOver(event.x(),event.y())) {
            pair.setValue(pair==lowerField?Integer.toString(d.lodLowerTolerance()):pair==upperField?Integer.toString(d.lodUpperTolerance()):Double.toString(d.lodPlanHz()));return true;
        }
        if(adaptiveButton.visible && adaptiveButton.isMouseOver(event.x(),event.y())) {adaptiveLods=d.adaptiveLods();adaptiveButton.setMessage(adaptiveLabel());updateWidgetStates();return true;}
        if(asyncButton.visible && asyncButton.isMouseOver(event.x(),event.y())) {asyncPlanning=d.asyncLodPlanning();asyncButton.setMessage(asyncLabel());return true;}
        if(generatePlanesButton.visible && generatePlanesButton.isMouseOver(event.x(),event.y())) {generatePlanes=d.generatePlaneTiles();generatePlanesButton.setMessage(generatePlanesLabel());updateWidgetStates();return true;}
        if(generateVoxelsButton.visible && generateVoxelsButton.isMouseOver(event.x(),event.y())) {generateVoxels=d.generateVoxelTiles();generateVoxelsButton.setMessage(generateVoxelsLabel());updateWidgetStates();return true;}
        if(structuresButton.visible && structuresButton.isMouseOver(event.x(),event.y())) {generateStructures=d.generateStructures();structuresButton.setMessage(structuresLabel());return true;}
        if(treesButton.visible && treesButton.isMouseOver(event.x(),event.y())) {generateTrees=d.generateTrees();treesButton.setMessage(treesLabel());return true;}
        if(sampleSpacingField.visible && sampleSpacingField.isMouseOver(event.x(),event.y())) {setSampleSpacing((int)d.samplePixels());return true;}
        if(packedQuadButton.visible && packedQuadButton.isMouseOver(event.x(),event.y())) { packedQuadMesh=d.packedQuadMesh();packedQuadButton.setMessage(packedQuadLabel());return true; }
        if(screenSpaceButton.visible && screenSpaceButton.isMouseOver(event.x(),event.y())) { screenSpaceLod=d.screenSpaceLod();screenSpaceButton.setMessage(screenSpaceLabel());return true; }
        if(plannedLodsButton.visible && plannedLodsButton.isMouseOver(event.x(),event.y())) {showPlannedLods=d.showPlannedLods();plannedLodsButton.setMessage(plannedLodsLabel());return true;}
        if(previewButton.visible && previewButton.isMouseOver(event.x(),event.y())) { previewMode=d.previewMode(); previewButton.setMessage(previewLabel()); return true; }
        if(noiseEnabledButton.visible && noiseEnabledButton.isMouseOver(event.x(),event.y())) {noiseEnabled=d.noiseEnabled();noiseEnabledButton.setMessage(noiseEnabledLabel());return true;}
        if(scatterModeButton.visible && scatterModeButton.active && scatterModeButton.isMouseOver(event.x(),event.y())) { scatterMode=d.scatterMode(); scatterModeButton.setMessage(scatterModeLabel()); return true; }
        if(hiZPollRateField.visible && hiZPollRateField.isMouseOver(event.x(),event.y())) {hiZPollRateField.setValue(Integer.toString(d.hiZDataPollRate()));return true;}
        if(planeThresholdField.visible && planeThresholdField.isMouseOver(event.x(),event.y())) { planeThresholdField.setValue(Integer.toString(d.planeThreshold())); return true; }
        if(customDistanceField.visible && customDistanceField.isMouseOver(event.x(),event.y())) { customDistanceField.setValue(Integer.toString(d.renderDistance())); error=""; return true; }
        if(qualityButton.visible && qualityButton.isMouseOver(event.x(),event.y())) {setSampleSpacing((int)d.samplePixels());return true;}
        if(performanceButton.visible && performanceButton.isMouseOver(event.x(),event.y())) { performance=d.performance(); updateEffortButton(); return true; }
        if(advancedHudButton.visible && advancedHudButton.isMouseOver(event.x(),event.y())) { advancedHud=d.advancedHud();advancedHudButton.setMessage(advancedHudLabel());return true; }
        if(performanceHudButton.visible && performanceHudButton.isMouseOver(event.x(),event.y())) { performanceHud=d.performanceHud(); performanceHudButton.setMessage(performanceHudLabel()); return true; }
        if(triangleHudButton.visible && triangleHudButton.isMouseOver(event.x(),event.y())) {
            triangleHudMode=d.triangleHudMode();triangleHudButton.setMessage(triangleHudLabel());return true;
        }
        if(tileDetailHudButton.visible && tileDetailHudButton.isMouseOver(event.x(),event.y())) {
            tileDetailHud=d.tileDetailHud();tileDetailHudButton.setMessage(tileDetailHudLabel());return true;
        }
        if(hiZDetailsHudButton.visible && hiZDetailsHudButton.isMouseOver(event.x(),event.y())) {
            hiZDetailsHud=d.hiZDetailsHud();hiZDetailsHudButton.setMessage(hiZDetailsHudLabel());return true;
        }
        if(colorByLodButton.visible && colorByLodButton.isMouseOver(event.x(),event.y())) { colorByLod=d.colorByLod(); colorByLodButton.setMessage(colorByLodLabel()); return true; }
        if(lodFogButton.visible && lodFogButton.isMouseOver(event.x(),event.y())) { lodFog=d.lodDistanceFog(); lodFogButton.setMessage(lodFogLabel()); return true; }
        if(wireframeButton.visible && wireframeButton.isMouseOver(event.x(),event.y())) { wireframe=d.wireframe(); wireframeButton.setMessage(wireframeLabel()); return true; }
        if(frustumEdgesButton.visible && frustumEdgesButton.isMouseOver(event.x(),event.y())) {frustumCullingEdges=d.frustumCullingEdges();frustumEdgesButton.setMessage(frustumEdgesLabel());return true;}
        if(backfaceButton.visible && backfaceButton.isMouseOver(event.x(),event.y())) {gpuBackfaceCulling=d.gpuBackfaceCulling();backfaceButton.setMessage(backfaceLabel());return true;}
        if(frustumCullingButton.visible && frustumCullingButton.isMouseOver(event.x(),event.y())) {
            frustumCulling=d.frustumCulling();frustumCullingButton.setMessage(frustumCullingLabel());return true;
        }
        if(hiZPaintButton.visible && hiZPaintButton.isMouseOver(event.x(),event.y())) {hiZPaint=d.hiZPaint();hiZPaintButton.setMessage(hiZPaintLabel());return true;}
        if(hiZButton.visible && hiZButton.isMouseOver(event.x(),event.y())) {hiZOcclusion=d.hiZOcclusion();hiZButton.setMessage(hiZLabel());return true;}
        if(randomizedTexturesButton.visible && randomizedTexturesButton.isMouseOver(event.x(),event.y())) {randomizedTextures=d.randomizedTextures();randomizedTexturesButton.setMessage(randomizedTexturesLabel());return true;}
        if(lockedOptionsButton.visible && lockedOptionsButton.isMouseOver(event.x(),event.y())) {lockedOptions=d.lockedOptions();lockedOptionsButton.setMessage(lockedOptionsLabel());return true;}
        if(hideLockedOptionsButton.visible && hideLockedOptionsButton.isMouseOver(event.x(),event.y())) {hideLockedOptions=d.hideLockedOptions();hideLockedOptionsButton.setMessage(hideLockedOptionsLabel());return true;}
        if(worldgenProfilingButton.visible && worldgenProfilingButton.isMouseOver(event.x(),event.y())) {
            worldgenProfiling=d.worldgenProfiling();worldgenProfilingButton.setMessage(worldgenProfilingLabel());return true;
        }
        if(scratchTerrainButton.visible && scratchTerrainButton.isMouseOver(event.x(),event.y())) {
            scratchTerrain=d.generation().scratchTerrain();scratchTerrainButton.setMessage(scratchTerrainLabel());return true;
        }
        if(selectedSurfaceButton.visible && selectedSurfaceButton.isMouseOver(event.x(),event.y())) {
            selectedSurface=d.generation().selectedSurface();selectedSurfaceButton.setMessage(selectedSurfaceLabel());return true;
        }
        if(tileGenPoolButton.visible && tileGenPoolButton.isMouseOver(event.x(),event.y())) {
            tileGenPool=d.generation().poolEnabled();tileGenPoolButton.setMessage(tileGenPoolLabel());updateWidgetStates();return true;
        }
        if(genPoolLimit.visible && genPoolLimit.active && genPoolLimit.isMouseOver(event.x(),event.y())) {
            genPoolLimit.setNumber(d.generation().poolMultiplier());return true;
        }
        if(genPoolMemory.visible && genPoolMemory.active && genPoolMemory.isMouseOver(event.x(),event.y())) {
            genPoolMemory.setNumber(d.generation().poolMiB());return true;
        }
        if(mipmapButton.visible && mipmapButton.isMouseOver(event.x(),event.y())) {mipmapMode=d.mipmapMode();mipmapButton.setMessage(mipmapLabel());return true;}
        if(anisotropicButton.visible && anisotropicButton.isMouseOver(event.x(),event.y())) {anisotropicFiltering=d.anisotropicFiltering();anisotropicButton.setMessage(anisotropicLabel());return true;}
        if(opaqueLeavesButton.visible && opaqueLeavesButton.isMouseOver(event.x(),event.y())) {opaqueLeaves=d.opaqueLeaves();opaqueLeavesButton.setMessage(opaqueLeavesLabel());return true;}
        if(fogButton.visible && fogButton.isMouseOver(event.x(),event.y())) { disableFog=d.disableDistanceFog(); fogButton.setMessage(fogLabel()); return true; }
        if(gpuSurfaceButton.visible && gpuSurfaceButton.isMouseOver(event.x(),event.y())) { gpuSurfaceSampling=d.gpuSurfaceSampling(); gpuSurfaceButton.setMessage(gpuSurfaceLabel()); return true; }
        if(voxelButton.visible && voxelButton.isMouseOver(event.x(),event.y())) { voxelTerrain=d.voxelTerrain(); voxelButton.setMessage(voxelLabel()); return true; }
        if(renderTilesButton.visible && renderTilesButton.isMouseOver(event.x(),event.y())) {renderTiles=d.renderTiles();renderTilesButton.setMessage(renderTilesLabel());return true;}
        if(drawCallsButton.visible && drawCallsButton.isMouseOver(event.x(),event.y())) {drawCalls=d.drawCalls();drawCallsButton.setMessage(drawCallsLabel());return true;}
        if(enabledButton.visible && enabledButton.isMouseOver(event.x(),event.y())) { enabled=d.enabled(); enabledButton.setMessage(enabledLabel()); return true; }
        if(clipButton.visible && clipButton.isMouseOver(event.x(),event.y())) { clipDistance=d.clipDistance(); clipButton.setMessage(clipLabel()); return true; }
        return false;
    }
    @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,boolean doubleClick) {
        if(resetAt(event)) return true;
        return super.mouseClicked(event,doubleClick);
    }
    @Override public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event,double dx,double dy) {
        if(resetAt(event)) return true;
        return super.mouseDragged(event,dx,dy);
    }
    private int sliderWorkerLimit() { return (int)sliders[1].number(); }
    private Component qualityLabel() {
        int spacing=sampleSpacing;
        var color=spacing==1?net.minecraft.ChatFormatting.GOLD:spacing==2?net.minecraft.ChatFormatting.YELLOW:net.minecraft.ChatFormatting.GREEN;
        var label=Component.literal("Graphics: ").append(Component.literal(new String[]{"Max","Quality","Fast","Fastest"}[spacing-1]).withStyle(color));
        return label;
    }
    private void updateGraphicsButton() {
        qualityButton.setMessage(qualityLabel());
        String description=switch(sampleSpacing) {
            case 4 -> "Fastest: 4 px sample spacing. Coarser terrain detail with the lowest generation and rendering cost.";
            case 3 -> "Fast (recommended): 3 px sample spacing. A good balance between terrain detail and performance.";
            case 2 -> "Quality: 2 px sample spacing. Finer terrain detail with more generation and rendering work.";
            default -> "Max: 1 px sample spacing. The finest terrain detail with the highest generation and rendering cost.";
        };
        qualityButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(description+" Click to cycle graphics presets.")));
    }
    private void updateEffortButton() {
        performanceButton.setMessage(performanceLabel());
        String description=switch(performance) {
            case 0 -> "Relax: use 30% of the Worker Limit for tile generation, leaving more CPU capacity for gameplay.";
            case 1 -> "Balanced: use 50% of the Worker Limit for tile generation, balancing generation speed and CPU load.";
            case 2 -> "Agressive: use 80% of the Worker Limit for faster tile generation with higher CPU load.";
            default -> "Max: use 100% of the Worker Limit for the highest generation effort and CPU load.";
        };
        performanceButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(description+" At least one worker is used. Click to cycle effort levels.")));
    }
    private Component performanceLabel() {
        var colors=new net.minecraft.ChatFormatting[]{net.minecraft.ChatFormatting.DARK_GREEN,net.minecraft.ChatFormatting.DARK_GREEN,net.minecraft.ChatFormatting.GOLD,net.minecraft.ChatFormatting.RED};
        return Component.literal("Generation Effort: ").append(Component.literal(new String[]{"Relax","Balanced","Agressive","Max"}[performance]).withStyle(colors[performance]));
    }
    private Component adaptiveLabel() {return coloredToggleLabel("Adaptive LOD",adaptiveLods);}
    private Component advancedHudLabel() { return coloredToggleLabel("Render Details",advancedHud); }
    private Component performanceHudLabel() { return coloredToggleLabel("Performance",performanceHud); }
    private Component triangleHudLabel() {
        return switch(triangleHudMode) {
            case OFF -> coloredValueLabel("Triangle","Off",ChatFormatting.RED);
            case BASIC -> coloredValueLabel("Triangle","Basic",ChatFormatting.YELLOW);
            case DETAILED -> coloredValueLabel("Triangle","Detailed",ChatFormatting.GREEN);
        };
    }
    private Component tileDetailHudLabel() { return coloredToggleLabel("LoD Sector",tileDetailHud); }
    private Component hiZDetailsHudLabel() { return coloredToggleLabel("Hi-Z Details",hiZDetailsHud); }
    private Component wireframeLabel() { return debugToggleLabel("Wireframe",wireframe); }
    private Component asyncLabel() { return coloredToggleLabel("Async LOD Planning",asyncPlanning); }
    private void configureLinked(EditBox field,int index,int value) {
        field.setMaxLength(3);field.setValue(Integer.toString(value));
        field.setResponder(text -> {try { int n=Integer.parseInt(text);if(n>=sliders[index].min && n<=sliders[index].max)sliders[index].setNumber(n); }catch(NumberFormatException ignored) { }});
        addRenderableWidget(field);
    }
    private Component generatePlanesLabel() {return coloredToggleLabel("Generate Plane Tiles",generatePlanes);}
    private Component generateVoxelsLabel() {return coloredToggleLabel("Generate Voxel Tiles",generateVoxels);}
    private Component structuresLabel() {return coloredToggleLabel("Generate Structures",generateStructures);}
    private Component treesLabel() {return coloredToggleLabel("Generate Trees",generateTrees);}
    private void setSampleSpacing(int pixels) {
        sampleSpacing=pixels;sampleSpacingField.setValue(Integer.toString(pixels));
        if(qualityButton!=null)updateGraphicsButton();
    }
    private Component packedQuadLabel() { return coloredToggleLabel("Packed Quad Mesh",packedQuadMesh); }
    private Component screenSpaceLabel() { return coloredToggleLabel("Screen-Space LOD",screenSpaceLod); }
    private Component gpuSurfaceLabel() { return coloredToggleLabel("GPU Sampling",gpuSurfaceSampling); }
    private Component freecamLabel() { return debugToggleLabel("FreeCam",Freecam.freecamActive()); }
    private Component debugcamLabel() { return debugToggleLabel("DebugCam",Freecam.debugCamActive()); }
    private void updateCameraLabels() { freecamButton.setMessage(freecamLabel()); debugcamButton.setMessage(debugcamLabel()); }
    private void updateWidgetStates() {
        voxelButton.active=generateVoxels && generatePlanes;packedQuadButton.active=generateVoxels;planeThresholdField.active=generatePlanes && generateVoxels;
        if(!planeThresholdField.active && getFocused()==planeThresholdField)setFocused(null);
        sliders[17].active=adaptiveLods;
        genPoolLimit.active=tileGenPool;
        genPoolMemory.active=tileGenPool;
        if(!tileGenPool && (getFocused()==genPoolLimit || getFocused()==genPoolMemory))setFocused(null);
        for(var widget:noiseWidgets) {
            widget.active=widget==noiseEnabledButton || noiseEnabled;
            if(!widget.active && getFocused()==widget) setFocused(null);
        }
        if(applyButton!=null) {
            try { applyButton.active=!draftValues().equals(LodSettings.get()); }
            catch(IllegalArgumentException e) { applyButton.active=true; }
        }
    }
    private static Component coloredToggleLabel(String name,boolean on) {
        return coloredValueLabel(name,on?"On":"Off",on?ChatFormatting.GREEN:ChatFormatting.RED);
    }
    private static Component debugToggleLabel(String name,boolean on) {
        return coloredValueLabel(name,on?"On":"Off",on?DEBUG_ON:DEBUG_OFF);
    }
    private static Component coloredValueLabel(String name,String value,int color) {
        return Component.literal(name+": ").append(Component.literal(value).withColor(color));
    }
    private static Component coloredValueLabel(String name,String value,ChatFormatting color) {
        return Component.literal(name+": ").append(Component.literal(value).withStyle(color));
    }
    private Component voxelLabel() { return coloredToggleLabel("Smooth Terrain",!voxelTerrain); }
    private Component frustumEdgesLabel() {return debugToggleLabel("Frustum Culling Edges",frustumCullingEdges);}
    private Component backfaceLabel() {return coloredToggleLabel("GPU Backface Culling",gpuBackfaceCulling);}
    private Component frustumCullingLabel() {return coloredToggleLabel("Frustum Culling",frustumCulling);}
    private Component hiZPaintLabel() {return debugToggleLabel("Paint Hi-Z Culled",hiZPaint);}
    private Component hiZLabel() {return coloredToggleLabel("Hi-Z Culling",hiZOcclusion);}
    private Component randomizedTexturesLabel() {return coloredToggleLabel("Randomized Textures",randomizedTextures);}
    private Component lockedOptionsLabel() { return coloredToggleLabel("Locked Options",lockedOptions); }
    private Component hideLockedOptionsLabel() { return coloredToggleLabel("Hide Locked Options",hideLockedOptions); }
    private Component worldgenProfilingLabel() { return coloredToggleLabel("Worldgen Profiling",worldgenProfiling); }
    private Component scratchTerrainLabel() {
        return coloredValueLabel("On-Demand Scratch Terrain",scratchTerrain.label(),
            scratchTerrain==LodSettings.ScratchTerrain.OFF?ChatFormatting.RED:ChatFormatting.GREEN);
    }
    private Component tileGenPoolLabel() { return coloredToggleLabel("Tile Gen. Pool",tileGenPool); }
    private Component selectedSurfaceLabel() { return coloredToggleLabel("Selected Surface Columns",selectedSurface); }
    private Component mipmapLabel() {
        var color=switch(mipmapMode) {
            case OFF -> ChatFormatting.RED;
            case LOD_ONLY -> ChatFormatting.YELLOW;
            case GLOBAL -> ChatFormatting.GREEN;
        };
        return coloredValueLabel("Force Mipmap",mipmapMode.label(),color);
    }
    private Component anisotropicLabel() {
        return anisotropicFiltering==1?coloredValueLabel("Anisotropic Filtering","Off",ChatFormatting.RED)
            :Component.literal("Anisotropic Filtering: "+anisotropicFiltering+"x");
    }
    private Component opaqueLeavesLabel() {return coloredToggleLabel("Opaque Leaves",opaqueLeaves);}
    private Component fogLabel() { return coloredToggleLabel("Disable vanilla fog",disableFog); }
    private Component lodFogLabel() { return coloredToggleLabel("LOD Fog",lodFog); }
    private Component colorByLodLabel() { return debugToggleLabel("Paint Lod Levels",colorByLod); }
    private Component renderTilesLabel() { return coloredToggleLabel("Render Tiles",renderTiles); }
    private Component drawCallsLabel() { return coloredToggleLabel("Draw Calls",drawCalls); }
    private Component enabledLabel() { return coloredToggleLabel("Generate Tiles",enabled); }
    private Component clipLabel() { return coloredToggleLabel("Clip Chunks",clipDistance); }
    private LodSettings.Values draftValues() {
        int pixels=Integer.parseInt(sampleSpacingField.getValue());
        int distance=Integer.parseInt(customDistanceField.getValue());
        if(distance<32 || distance>5_303_300) throw new IllegalArgumentException("Invalid custom distance");
        int planeThreshold=Integer.parseInt(planeThresholdField.getValue());
        int pollRate=Integer.parseInt(hiZPollRateField.getValue());
        int strength=(int)sliders[10].number(),scale=(int)sliders[11].number(),offset=(int)sliders[12].number();
        return new LodSettings.Values(pixels,(int)sliders[1].number(),disableFog,clipDistance,distance,sliders[3].number(),sliders[4].number(),enabled,voxelTerrain,wireframe,(int)sliders[5].number(),lodFog,previewMode,colorByLod,(int)sliders[6].number(),(int)sliders[7].number(),performanceHud,triangleHudMode,tileDetailHud,gpuSurfaceSampling,performance,planeThreshold,strength,scale,offset,(int)sliders[13].number(),(int)sliders[14].number(),scatterMode,noiseEnabled,opaqueLeaves,hiZOcclusion,hiZPaint,gpuBackfaceCulling,pollRate,frustumCullingEdges,screenSpaceLod,Integer.parseInt(lowerField.getValue()),Integer.parseInt(upperField.getValue()),asyncPlanning,Double.parseDouble(planRateField.getValue()),(int)sliders[17].number(),advancedHud,renderTiles,drawCalls,packedQuadMesh,adaptiveLods,showPlannedLods,generatePlanes,generateVoxels,generateStructures,generateTrees,anisotropicFiltering,randomizedTextures,mipmapMode,lockedOptions,hideLockedOptions,worldgenProfiling,new LodSettings.GenerationOptions(scratchTerrain,tileGenPool,(int)genPoolLimit.number(),(int)genPoolMemory.number(),selectedSurface),hiZDetailsHud,frustumCulling);
    }
    private void save() {
        try {
            var previous=LodSettings.get();
            var next=draftValues();
            LodSettings.save(next);
            ClientOptionLocks.settingsApplied(previous.lockedOptions());
            MipmapOverride.settingsApplied(previous);
            dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.setEnabled(next.worldgenProfiling());
            error="";
        }
        catch(IllegalArgumentException e) { error="Invalid value: check the allowed range."; }
        catch(java.io.IOException e) { error="Could not save settings: "+e.getMessage(); }
        updateWidgetStates();
    }
    private Component previewLabel() {
        return switch(previewMode) {
            case 0 -> coloredValueLabel("LOD Outlines","Off",DEBUG_OFF);
            case 2 -> Component.literal("Edges").withColor(DEBUG_MIDDLE);
            default -> Component.literal("Edges + Transitions").withColor(DEBUG_ON);
        };
    }
    private Component plannedLodsLabel() { return debugToggleLabel("Show Planned Lods",showPlannedLods); }
    private Component noiseEnabledLabel() { return coloredToggleLabel("Noise",noiseEnabled); }
    private Component scatterModeLabel() { return Component.literal("Noise Type: "+(scatterMode==0?"Noise":"Dither")); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float a) {
        updateWidgetStates(); updateCameraLabels();
        super.extractRenderState(graphics,mouseX,mouseY,a);
        if(error.isEmpty()) graphics.centeredText(font,title,width/2,Math.max(4,contentTop-font.lineHeight-4),0xffffffff);
        else graphics.centeredText(font,error,width/2,Math.max(4,contentTop-font.lineHeight-4),0xffff7777);
        switch(selected) {
            case BASIC -> { }
            case ADVANCED -> renderAdvancedLabels(graphics);
            case DEVTOOLS -> renderDevToolsLabels(graphics);
        }
        renderPageScrollbar(graphics);
    }
    @Override public boolean isPauseScreen() { return false; }
    private static String format(double value) { return String.format(java.util.Locale.ROOT,"%.2f",value); }
    private static void digitsOnly(EditBox box) {
        String[] last={box.getValue()};
        box.setResponder(text -> { if(!text.matches("[0-9]*")) box.setValue(last[0]); else last[0]=text; });
    }
    private final class SettingSlider extends AbstractSliderButton {
        private final String name; private final double min,max; private final int index; private final double[] options;
        SettingSlider(int x,int y,int width,String name,double min,double max,double initial,int index) {
            super(x,y,width,20,Component.empty(),0);
            this.name=name; this.min=min; this.max=max; this.index=index;
            options=switch(index) {
                case 2 -> DISTANCE_OPTIONS;
                case 18 -> LodSettings.GEN_POOL_MULTIPLIERS;
                case 19 -> LodSettings.GEN_POOL_MIB;
                default -> null;
            };
            setNumber(initial); updateMessage();
        }
        void setNumber(double number) {
            if(index==3 && number==0) number=max;
            if(options!=null) {
                int selected=0;
                for(int i=1;i<options.length;i++) {
                    if(Math.abs(options[i]-number)<Math.abs(options[selected]-number)) selected=i;
                }
                setValue(options.length==1?0:(double)selected/(options.length-1));
            } else setValue(max==min?0:(number-min)/(max-min));
        }
        @Override protected void setValue(double proposed) {
            int steps=options!=null?options.length-1:(index==13 || index==14)?(int)(max-min)/5:(int)(max-min);
            double snapped=steps<=0?0:Math.rint(Math.max(0,Math.min(1,proposed))*steps)/steps;
            super.setValue(snapped);
        }
        @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
            if(canChangeValue && (event.isLeft() || event.isRight())) {
                int steps=options!=null?options.length-1:(index==13 || index==14)?(int)(max-min)/5:(int)(max-min);
                if(steps>0) setValue(value+(event.isLeft()?-1.0:1.0)/steps);
                return true;
            }
            return super.keyPressed(event);
        }
        private double number() { if(index==3 && Math.rint(min+value*(max-min))==max) return 0; return options!=null?options[(int)Math.round(value*(options.length-1))]:max==min?min:Math.rint(min+value*(max-min)); }
        @Override protected void updateMessage() {
            double n=number();
            String display=index==3 && n==0?"Smart":index==5 && n<0?"Off":index<=2 || index>=5?Integer.toString((int)n):n<1?Math.round(n*1024)+" MiB":Integer.toString((int)n)+" GiB";
            if(index==2 && customDistanceField!=null && !customDistanceField.getValue().isEmpty()) display=customDistanceField.getValue();
            if(index==13 || index==14) display=format(n/100);
            if(index==18) display=(int)n+"×";
            if(index==19) display=n==0?"Auto":(int)n+" MiB";
            setMessage(index==5 && n<0?coloredValueLabel(name,display,ChatFormatting.RED):Component.literal(name+": "+display));
        }
        @Override protected void applyValue() {
            EditBox linked=index==15?lowerField:index==16?upperField:null;
            if(linked!=null && !linked.getValue().equals(Integer.toString((int)number())))linked.setValue(Integer.toString((int)number()));
            if(index==2 && customDistanceField!=null && !syncingDistance) customDistanceField.setValue(Integer.toString((int)number()));
        }
    }
}
