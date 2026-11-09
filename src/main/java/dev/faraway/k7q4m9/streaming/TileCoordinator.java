package dev.faraway.k7q4m9.streaming;

import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Phase;

import dev.faraway.k7q4m9.mesh.TerrainMeshBuilder;
import dev.faraway.k7q4m9.mesh.TileMesh;
import dev.faraway.k7q4m9.terrain.LodTile;
import dev.faraway.k7q4m9.terrain.TileKey;
import dev.faraway.k7q4m9.worldgen.SurfaceSample;
import dev.faraway.k7q4m9.worldgen.CachePause;
import dev.faraway.k7q4m9.worldgen.DetachedDecorationSampler;
import dev.faraway.k7q4m9.worldgen.ExistingTreeBlocks;
import dev.faraway.k7q4m9.worldgen.WorldTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import dev.faraway.k7q4m9.worldgen.WorldDefinition;
import dev.faraway.k7q4m9.client.LodSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One coordinator per world session. update/poll/upload acknowledgement belong to the client owner. */
public final class TileCoordinator implements AutoCloseable {
    private final boolean voxel=dev.faraway.k7q4m9.client.LodSettings.get().voxelTerrain();
    private boolean generatePlanes=LodSettings.get().generatePlaneTiles(),generateVoxels=LodSettings.get().generateVoxelTiles();
    private boolean generateStructures=LodSettings.get().generateStructures(),generateTrees=LodSettings.get().generateTrees();
    private boolean opaqueLeaves=LodSettings.get().opaqueLeaves();
    private boolean packedQuadMesh=LodSettings.get().packedQuadMesh();
    private volatile int planeThreshold=dev.faraway.k7q4m9.client.LodSettings.get().planeThreshold();
    private static final long RESULT_RESERVATION_BYTES=8L*1024*1024;
    private final WorldDefinition world;
    private final CoveragePlanner planner=new CoveragePlanner();
    private final CoveragePlanner prefetchPlanner=new CoveragePlanner();
    private final CoveragePlanner retentionPlanner=new CoveragePlanner();
    private final ByteBudget outputs=new ByteBudget(32L*1024*1024);
    private final ByteBudget retainedSamples=new ByteBudget(1);
    private final ConcurrentHashMap<TileKey,Entry> entries=new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Completed> completed=new ConcurrentLinkedQueue<>();
    private final Object publicationLock=new Object();
    // Pool accounting and entry ownership are protected by publicationLock.
    private final HashMap<Entry,PartialTile> partialTiles=new HashMap<>();
    private long partialReservedBytes,parkedBytes,partialByteLimit,sampleAndPoolBytes;
    private int parkedTiles,partialTileLimit;
    private final int workerCount=Math.max(1,Runtime.getRuntime().availableProcessors());
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(workerCount,workerCount,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(workerCount),r -> {
        var thread=new Thread(r,"FarAway terrain"); thread.setDaemon(true); thread.setPriority(Thread.NORM_PRIORITY-1); return thread;
    });
    private final WorldTerrainSampler.Shared surfaceCache;
    private final ExistingTreeBlocks.Storage existingTrees;
    private final dev.faraway.k7q4m9.worldgen.DecorationCache decorationCache=new dev.faraway.k7q4m9.worldgen.DecorationCache();
    private final dev.faraway.k7q4m9.worldgen.DetachedTerrainCache terrainCache=new dev.faraway.k7q4m9.worldgen.DetachedTerrainCache();
    private final ThreadLocal<WorldTerrainSampler> workerSampler;
    private final ThreadLocal<DetachedDecorationSampler> structureSampler;
    private volatile int structureRadius=4096;
    private final java.util.concurrent.atomic.LongAdder sampledTiles=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder samplingNanos=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder meshingNanos=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.AtomicInteger inFlight=new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.LongAdder cancelledTiles=new java.util.concurrent.atomic.LongAdder();
    private volatile int admissionLimit;
    public int activeWorkerLimit() { return admissionLimit; }
    public int runningWorkers() { return executor.getActiveCount(); }
    public int queuedUploads() { return completed.size(); }
    public int queuedBuilds() { return executor.getQueue().size(); }
    public int inFlightBuilds() { return inFlight.get(); }
    public record PoolSnapshot(int waiting,int limit,long bytes,long byteLimit) {}
    public PoolSnapshot poolSnapshot() {
        synchronized(publicationLock) {
            return new PoolSnapshot(parkedTiles,partialTileLimit,parkedBytes,availablePoolBytes());
        }
    }
    private volatile boolean closed;
    private Set<TileKey> wanted=Set.of();
    private volatile Set<TileKey> requested=Set.of();
    private long nextRevision;
    private volatile double cameraX,cameraZ;
    private boolean prefetchEnabled=true;
    private double lastVramMode=Double.NaN;
    private boolean lastAdaptiveLods;
    private Set<TileKey> drawn=Set.of();
    // Conservative planning allowance for tile bookkeeping and transient heap data.
    private static final long ESTIMATED_HEAP_PER_TILE=64L*1024;
    private final LodPlanningSession planning=new LodPlanningSession();
    public DirectLodPlanner.Plan acceptedPlan() { return cachedPlan; }
    private LodScatter.Config scatterConfig;
    private DirectLodPlanner.Plan cachedPlan;
    private int planX,planY,planZ,planRadius,planHeight,planLimit;
    private double planFov,planPixels;
    private ScreenSpaceLod projection,plannedProjection;
    public void setProjection(ScreenSpaceLod value) { projection=value; }
    private long lastPlanNanos;
    private boolean benchmarkPlanLocked;
    private Set<TileKey> benchmarkBoundary=Set.of();
    public int[] benchmarkBoundaryProgress() {
        return new int[]{(int)benchmarkBoundary.stream().filter(this::resident).count(),benchmarkBoundary.size()};
    }
    public double benchmarkRemainingSeconds() {
        var durations=entries.values().stream().filter(e -> wanted.contains(e.key) && e.state==State.RESIDENT && e.generationNanos>=0).mapToLong(e -> e.generationNanos).summaryStatistics();
        if(durations.getCount()==0) return Double.NaN;
        return durations.getAverage()*Math.max(0,wanted.size()-durations.getCount())/Math.max(1,admissionLimit)/1_000_000_000.0;
    }
    private volatile boolean budgetLimited;
    private double cameraY;

    public TileCoordinator(WorldDefinition world) {
        this.world=world;
        var settings=LodSettings.get();
        scatterConfig=new LodScatter.Config(settings.scatterStrength(),settings.scatterScale(),settings.scatterSeedOffset(),settings.noiseBottom(),settings.noiseTop(),settings.scatterMode(),settings.noiseEnabled());
        retainedSamples.setLimit(Math.max(1,LodSettings.toBytes(LodSettings.effectiveHeapGiB())/2));
        surfaceCache=new WorldTerrainSampler.Shared(world,() -> closed);
        existingTrees=world.detachedDecorations()?new ExistingTreeBlocks.Storage(world):null;
        workerSampler=ThreadLocal.withInitial(() -> new WorldTerrainSampler(world,surfaceCache));
        structureSampler=ThreadLocal.withInitial(() -> new DetachedDecorationSampler(world,surfaceCache.structureIndex(),terrainCache,workerSampler.get()));
    }
    /** Replan only changed representations; the previous resident stays drawable. */
    public void setPlaneThreshold(int threshold) {setRepresentation(threshold,LodSettings.get().generatePlaneTiles(),LodSettings.get().generateVoxelTiles());}
    private boolean useVoxelMesh(boolean planes) {return voxel || !planes;}
    private void setRepresentation(int threshold,boolean planes,boolean voxels) {
        boolean structures=LodSettings.get().generateStructures(),trees=LodSettings.get().generateTrees();
        boolean opaque=LodSettings.get().opaqueLeaves();
        boolean changed=generatePlanes!=planes || generateVoxels!=voxels || generateStructures!=structures || generateTrees!=trees || opaqueLeaves!=opaque;
        if(!changed && planeThreshold==threshold)return;
        if(!changed && (!planes || !voxels || !voxel)){planeThreshold=threshold;return;}
        synchronized(publicationLock) {
            planeThreshold=threshold;generatePlanes=planes;generateVoxels=voxels;generateStructures=structures;generateTrees=trees;opaqueLeaves=opaque;
            boolean allowed=planes || voxels;
            for(var old:entries.values()) {
                var resident=old.resident;
                boolean desiredPlane=resident!=null && shouldUsePlane(old.key,resident.seaLevel,resident.maxY,threshold,planes,voxels,voxel);
                boolean desiredVoxel=!desiredPlane && useVoxelMesh(planes);
                boolean needsBuild=allowed && (resident==null || desiredPlane!=resident.plane || desiredVoxel!=resident.voxelMesh || trees!=resident.trees || (!desiredPlane && (trees || structures) && old.key.spacing()==1 && opaque!=resident.opaqueLeaves) || (!desiredPlane && supportsStructures(old.key) && structures!=resident.structures));
                if(!needsBuild && old.state==State.RESIDENT)continue;
                var next=new Entry(old.key,++nextRevision,threshold);
                next.resident=resident;next.generationNanos=old.generationNanos;next.lastUsedNanos=old.lastUsedNanos;
                next.state=needsBuild || resident==null?State.MISSING:State.RESIDENT;
                entries.replace(old.key,old,next);
            }
        }
    }
    public static boolean shouldUsePlane(TileKey key,int seaLevel,int maxY,int threshold,boolean forcePlane,boolean voxel) {
        return shouldUsePlane(key,seaLevel,maxY,threshold,true,!forcePlane,voxel);
    }
    public static boolean shouldUsePlane(TileKey key,int seaLevel,int maxY,int threshold,boolean planes,boolean voxels,boolean voxel) {
        return planes && (!voxels || voxel && dev.faraway.k7q4m9.terrain.FarPlaneTile.usePlaneForMaximum(key,seaLevel,maxY,threshold));
    }
    private void updateQuadFormat() {
        boolean nextFormat=LodSettings.get().packedQuadMesh();if(nextFormat==packedQuadMesh)return;
        synchronized(publicationLock) {
            packedQuadMesh=nextFormat;
            if(!generateVoxels || !useVoxelMesh(generatePlanes))return;
            for(var old:entries.values()) {
                if(old.key.spacing()==1 || (old.resident!=null && old.resident.plane && old.state==State.RESIDENT))continue;
                var next=new Entry(old.key,++nextRevision,planeThreshold);
                next.resident=old.resident;next.generationNanos=old.generationNanos;next.lastUsedNanos=old.lastUsedNanos;
                entries.replace(old.key,old,next);
            }
        }
    }
    public static boolean representationNeedsReplacement(TileKey key,int seaLevel,int maxY,boolean currentlyPlane,int threshold) {
        return dev.faraway.k7q4m9.terrain.FarPlaneTile.usePlaneForMaximum(key,seaLevel,maxY,threshold)!=currentlyPlane;
    }
    public String productionStatus() {
        long tiles=sampledTiles.sum();
        return "budgetLimited="+budgetLimited+" visibleLevels="+drawn.stream().map(TileKey::spacing).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new))+" visible="+drawn.size()+" target="+requested.size()+" workers="+workerCount+" admission="+admissionLimit+" inFlight="+inFlight.get()+" prefetch="+prefetchEnabled+" cancelled="+cancelledTiles.sum()+" produced="+tiles+" sampleMs="+(tiles==0?0:samplingNanos.sum()/tiles/1_000_000)
            +" meshMs="+(tiles==0?0:meshingNanos.sum()/tiles/1_000_000)+" "+surfaceCache.status()+" "+terrainCache.status();
    }

    public void update(int x,int z,int radiusChunks,int maxTiles,int gameFps) {
        update(x,0,z,radiusChunks,maxTiles,gameFps,1080,70,1);
    }
    public void update(int x,int y,int z,int radiusChunks,int maxTiles,int gameFps,int screenHeight,double fov,double quality) {
        if(closed) return;
        if(world.mode()==WorldDefinition.Mode.LOADED_CHUNKS)
            radiusChunks=Math.min(radiusChunks,net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance()+1);
        setRepresentation(LodSettings.get().planeThreshold(),LodSettings.get().generatePlaneTiles(),LodSettings.get().generateVoxelTiles());
        updateQuadFormat();
        if(lastVramMode!=LodSettings.get().vramGiB() || lastAdaptiveLods!=LodSettings.get().adaptiveLods()) { prefetchEnabled=true;lastVramMode=LodSettings.get().vramGiB();lastAdaptiveLods=LodSettings.get().adaptiveLods(); }
        cameraX=x; cameraY=y; cameraZ=z; structureRadius=Math.min(4096,radiusChunks*16);
        if(TileKey.WORLD) {
            if(LodSettings.get().enabled() || LodSettings.get().renderTiles() || LodSettings.get().genPreview()) updateDynamic(x,y,z,radiusChunks,maxTiles,screenHeight,fov,quality);
        } else {
        var next=planner.update(x,z,radiusChunks,maxTiles);
        // Prepare one tile beyond the measured disk before it becomes visible. A second
        // tile of retention prevents boundary oscillation from destroying ready buffers.
        // Both rings count against the same working-set limit, never against coverage.
        var prefetch=prefetchEnabled?prefetchPlanner.update(x,z,Math.addExact(radiusChunks,32),maxTiles):next;
        var retention=prefetchEnabled?retentionPlanner.update(x,z,Math.addExact(radiusChunks,64),maxTiles):next;
        var settings=dev.faraway.k7q4m9.client.LodSettings.get();
        var visibleNext=visibleLevels(next,settings.minLodVisible(),settings.maxLodVisible());
        var visiblePrefetch=visibleLevels(prefetch,settings.minLodVisible(),settings.maxLodVisible());
        if(!visibleNext.equals(wanted) || !visiblePrefetch.equals(requested)) {
            wanted=visibleNext; requested=visiblePrefetch;
            removeEntries(e -> !retention.contains(e.key) || !inVisibleRange(e.key,settings.minLodVisible(),settings.maxLodVisible()));
            for(TileKey key:visiblePrefetch) entries.computeIfAbsent(key,k -> new Entry(k,++nextRevision));
        }
        }
        long cacheNow=System.nanoTime();
        if(LodSettings.get().heapGiB()==0) {
            surfaceCache.expire(cacheNow); decorationCache.expire(cacheNow); terrainCache.expire(cacheNow);
        }
        for(var entry:entries.values()) {
            if(requested.contains(entry.key) || drawn.contains(entry.key) || entry.resident==null) entry.lastUsedNanos=cacheNow;
        }
        admissionLimit=LodSettings.activeWorkers();
        long configuredHeap=dev.faraway.k7q4m9.client.LodSettings.toBytes(dev.faraway.k7q4m9.client.LodSettings.effectiveHeapGiB());
        var generation=LodSettings.get().generation();
        updatePoolLimits(generation,configuredHeap);
        outputs.setLimit(Math.max(RESULT_RESERVATION_BYTES,Math.min(RESULT_RESERVATION_BYTES*workerCount*2,configuredHeap/8)));
        var runtime=Runtime.getRuntime();
        if(runtime.totalMemory()-runtime.freeMemory() >= runtime.maxMemory()*0.95) return;
        if(admissionLimit==0 || !LodSettings.get().generationEnabled()) return;
        long now=System.nanoTime();
        var candidates=new ArrayList<Entry>();
        boolean boundaryPhase=benchmarkPlanLocked && benchmarkBoundary.stream().anyMatch(key -> !resident(key));
        boolean primaryReady=wanted.stream().allMatch(this::resident);
        for(Entry entry:entries.values()) if((!LodSettings.get().adaptiveLods() || primaryReady || wanted.contains(entry.key)) && requested.contains(entry.key) && (!boundaryPhase || benchmarkBoundary.contains(entry.key)) && entry.state==State.MISSING && entry.retryAt<=now && entry.failures<3) candidates.add(entry);
        candidates.sort(Comparator.<Entry>comparingInt(entry -> wanted.contains(entry.key)?0:1)
            .thenComparingDouble(entry -> priority(entry,now)));
        int submitted=dispatchReadyTiles(now);
        for(Entry entry:candidates) {
            if(submitted==workerCount || inFlight.get()>=admissionLimit || executor.getQueue().remainingCapacity()==0) break;
            if(generation.poolEnabled() && partialPoolFull()) break;
            if(generation.poolEnabled() && usePool(entry)) {
                long bytes=partialBytes(entry.key);
                if(canUsePartialBudget(bytes)) {
                    if(!startPartial(entry,generation,bytes)) break;
                    submitted++;
                    continue;
                }
            }
            var reservation=outputs.tryReserve(RESULT_RESERVATION_BYTES);
            if(reservation==null) break;
            entry.state=State.QUEUED; inFlight.incrementAndGet();
            long queuedAt=WorldgenProfiler.enabled()?System.nanoTime():0;
            try { executor.execute(() -> generate(entry,reservation,queuedAt,null,generation)); submitted++; }
            catch(RejectedExecutionException stopping) { entry.state=State.MISSING; inFlight.decrementAndGet(); reservation.close(); break; }
        }
    }

    private boolean usePool(Entry entry) {
        return (useVoxelMesh(entry.generatePlanes) || !entry.generateVoxels)
            && (entry.resident==null || entry.resident.samples==null || entry.generateStructures!=entry.resident.structures)
            && WorldTerrainSampler.resumableSurface(world,entry.key.spacing());
    }

    private static long partialBytes(TileKey key) {
        long stride=key.blocks()/key.spacing()+2L;
        return 512+48*stride*stride;
    }

    private long availablePoolBytes() {
        return Math.max(0,Math.min(partialByteLimit,sampleAndPoolBytes-retainedSamples.used()));
    }

    private boolean canUsePartialBudget(long bytes) {
        synchronized(publicationLock) {
            // A tile larger than the available pool can still use the ordinary worker path.
            return bytes<=partialByteLimit && (!partialTiles.isEmpty() || bytes<=availablePoolBytes());
        }
    }

    private boolean partialPoolFull() {
        synchronized(publicationLock) {
            return !partialTiles.isEmpty()
                && (partialTiles.size()>=partialTileLimit || partialReservedBytes>=availablePoolBytes());
        }
    }

    private void adjustSampleLimit() {
        retainedSamples.setLimit(Math.max(1,sampleAndPoolBytes-partialReservedBytes));
    }

    private void updatePoolLimits(LodSettings.GenerationOptions options,long heap) {
        synchronized(publicationLock) {
            sampleAndPoolBytes=heap/2;
            partialTileLimit=options.poolEnabled()?admissionLimit*options.poolMultiplier():0;
            partialByteLimit=options.poolEnabled()?Math.min(options.effectivePoolBytes(heap),sampleAndPoolBytes):0;
            var iterator=partialTiles.values().iterator();
            while(iterator.hasNext()) {
                var partial=iterator.next();
                if(partial.pause==null || poolAllowed(partial)) continue;
                iterator.remove();
                discardPartial(partial);
            }
            adjustSampleLimit();
        }
    }

    private boolean poolAllowed(PartialTile partial) {
        return workAllowed(partial.entry) && requested.contains(partial.entry.key) && LodSettings.get().generation().poolEnabled();
    }

    private boolean startPartial(Entry entry,LodSettings.GenerationOptions options,long bytes) {
        synchronized(publicationLock) {
            if(partialTiles.containsKey(entry) || partialTiles.size()>=partialTileLimit || bytes>availablePoolBytes()-partialReservedBytes) return false;
            var partial=new PartialTile(entry,options,adaptStructures(entry),bytes);
            partialTiles.put(entry,partial);
            partialReservedBytes+=bytes;
            adjustSampleLimit();
            if(submitPartial(partial,null)) return true;
            partialTiles.remove(entry);
            discardPartial(partial);
            return false;
        }
    }

    private int dispatchReadyTiles(long now) {
        synchronized(publicationLock) {
            if(partialTiles.isEmpty()) return 0;
            var ready=new ArrayList<PartialTile>();
            for(var partial:partialTiles.values())
                if(partial.pause!=null && partial.pause.readyAt!=0 && poolAllowed(partial)) ready.add(partial);
            ready.sort(Comparator.<PartialTile>comparingInt(partial -> wanted.contains(partial.entry.key)?0:1)
                .thenComparingDouble(partial -> priority(partial.entry,now)));
            int submitted=0;
            for(var partial:ready) {
                if(submitted==workerCount || inFlight.get()>=admissionLimit || executor.getQueue().remainingCapacity()==0) break;
                var output=partial.surfaceReady()?outputs.tryReserve(RESULT_RESERVATION_BYTES):null;
                if(partial.surfaceReady() && output==null) continue;
                if(!submitPartial(partial,output)) {
                    if(output!=null) output.close();
                    break;
                }
                submitted++;
            }
            return submitted;
        }
    }

    private boolean submitPartial(PartialTile partial,ByteBudget.Reservation output) {
        var pause=partial.pause;
        var entry=partial.entry;
        partial.pause=null;
        entry.state=State.QUEUED;
        inFlight.incrementAndGet();
        long queuedAt=WorldgenProfiler.enabled()?System.nanoTime():0;
        try {
            executor.execute(() -> {
                // A submitted stage takes ownership before it accesses the retained array.
                synchronized(publicationLock) {
                    if(pause!=null) {
                        parkedTiles--;
                        parkedBytes-=partial.bytes;
                    }
                }
                partial.chargePause(pause);
                if(output==null) samplePartial(partial,queuedAt);
                else generate(entry,output,queuedAt,partial,partial.options);
            });
            return true;
        } catch(RejectedExecutionException stopping) {
            inFlight.decrementAndGet();
            partial.pause=pause;
            entry.state=pause==null?State.MISSING:State.WAITING;
            return false;
        }
    }

    private boolean adaptStructures(Entry entry) {
        return entry.generateStructures && entry.generateVoxels && supportsStructures(entry.key)
            && DirectLodPlanner.intersects(entry.key,cameraX,cameraZ,structureRadius);
    }

    private void samplePartial(PartialTile partial,long queuedAt) {
        var entry=partial.entry;
        if(partial.started==0) {
            partial.started=System.nanoTime();
            partial.profile=WorldgenProfiler.tile(entry.key,entry.created,queuedAt);
        }
        var scope=WorldgenProfiler.scope(partial.profile,Phase.OTHER);
        Parked next=null;
        ByteBudget.Reservation output=null;
        try {
            if(!poolAllowed(partial)) return;
            entry.state=State.SAMPLING;
            var sampler=workerSampler.get();
            sampler.prepare(partial.adapted,partial.options);
            int spacing=entry.key.spacing(),stride=entry.key.blocks()/spacing+2;
            if(partial.samples==null) partial.samples=new SurfaceSample[Math.multiplyExact(stride,stride)];
            int ox=entry.key.originX()-spacing,oz=entry.key.originZ()-spacing;
            try(var ignored=CachePause.allow(true)) {
                for(;partial.nextSample<partial.samples.length;partial.nextSample++) {
                    if(!poolAllowed(partial)) return;
                    int i=partial.nextSample;
                    partial.samples[i]=sampler.sample(ox+i%stride*spacing,oz+i/stride*spacing,spacing);
                }
            }
            partial.sampledAt=System.nanoTime();
            next=new Parked(null);
        } catch(CachePause.Pending pending) {
            next=new Parked(pending.result());
        } catch(CancellationException cancelled) {
            // A replan or world change releases the partial array below.
        } catch(Exception failure) {
            scope.close();
            generationFailed(entry,partial.profile,failure);
        } finally {
            scope.close();
            synchronized(publicationLock) {
                if(next!=null && poolAllowed(partial)) {
                    if(partial.surfaceReady()) output=outputs.tryReserve(RESULT_RESERVATION_BYTES);
                    if(output==null) parkPartial(partial,next);
                } else {
                    partialTiles.remove(entry);
                    discardPartial(partial);
                    adjustSampleLimit();
                }
                if(output==null) inFlight.decrementAndGet();
            }
        }
        if(output!=null) generate(entry,output,queuedAt,partial,partial.options);
    }

    private void parkPartial(PartialTile partial,Parked pause) {
        partial.pause=pause;
        parkedTiles++;
        parkedBytes+=partial.bytes;
        partial.entry.state=State.WAITING;
    }

    private void releasePartialSamples(PartialTile partial) {
        synchronized(publicationLock) {
            if(partialTiles.remove(partial.entry,partial)) {
                partialReservedBytes-=partial.bytes;
                adjustSampleLimit();
            }
        }
    }

    private void discardPartial(PartialTile partial) {
        if(partial.pause!=null) {
            parkedTiles--;
            parkedBytes-=partial.bytes;
            partial.chargePause(partial.pause);
        }
        partialReservedBytes-=partial.bytes;
        if(partial.profile!=null) partial.profile.cancelled();
        if(current(partial.entry)) partial.entry.state=State.MISSING;
    }

    private void updateDynamic(int x,int y,int z,int radiusChunks,int maxTiles,int height,double fov,double samplePixels) {
        if(!dev.faraway.k7q4m9.client.FarAwayClient.benchmarkLocked()) { benchmarkPlanLocked=false; benchmarkBoundary=Set.of(); }
        var limits=dev.faraway.k7q4m9.client.LodSettings.get();
        var nextScatter=new LodScatter.Config(limits.scatterStrength(),limits.scatterScale(),limits.scatterSeedOffset(),limits.noiseBottom(),limits.noiseTop(),limits.scatterMode(),limits.noiseEnabled());
        if(!nextScatter.equals(scatterConfig)) { scatterConfig=nextScatter; cachedPlan=null; benchmarkPlanLocked=false; }
        long heapLimit=dev.faraway.k7q4m9.client.LodSettings.toBytes(LodSettings.effectiveHeapGiB());
        long gpuLimit=dev.faraway.k7q4m9.client.LodSettings.toBytes(LodSettings.effectiveVramGiB());
        int planLimit=(int)Math.min(Integer.MAX_VALUE,Math.min(heapLimit/ESTIMATED_HEAP_PER_TILE,gpuLimit/WorkingSetEstimate.TERRAIN_BYTES));
        var roots=planner.update(x,z,1,Math.max(64,planLimit));
        if(planLimit<roots.size()) { budgetLimited=true; benchmarkPlanLocked=dev.faraway.k7q4m9.client.FarAwayClient.benchmarkLocked(); return; }
        var request=new LodPlanningSession.Request(roots,x,y,z,world.heights().getMinY(),world.heights().getMaxY(),height,fov,samplePixels,planLimit,radiusChunks*16.0,scatterConfig,projection,limits.lodLowerTolerance(),limits.lodUpperTolerance());
        cachedPlan=planning.update(request,limits.asyncLodPlanning(),limits.lodPlanHz(),benchmarkPlanLocked);
        if(cachedPlan==null)return;
        if(dev.faraway.k7q4m9.client.FarAwayClient.benchmarkLocked())benchmarkPlanLocked=true;
        var plan=cachedPlan;
        var target=visibleLevels(plan.leaves(),limits.minLodVisible(),limits.maxLodVisible());
        if(benchmarkPlanLocked && !target.equals(wanted)) {
            benchmarkBoundary=BenchmarkBoundarySelection.select(target,x,y,z,world.heights().getMinY(),world.heights().getMaxY(),height,fov,samplePixels,radiusChunks*16.0,scatterConfig);
            dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.info("FarAway benchmark boundary selection: {} tiles, total targets={}",benchmarkBoundary.size(),target.size());
        }
        budgetLimited=plan.budgetLimited() && plan.deferred().stream().anyMatch(key -> Integer.numberOfTrailingZeros(key.spacing())>=limits.minLodVisible());
        if(benchmarkPlanLocked && budgetLimited) { wanted=Set.of(); requested=Set.of(); return; }
        var demand=new HashSet<TileKey>(target);
        if(limits.adaptiveLods() && prefetchEnabled) demand.addAll(adaptiveVariants(target,limits.minLodVisible(),limits.maxLodVisible(),limits.adaptiveMaxLod()));
        for(var key:demand) entries.computeIfAbsent(key,k -> new Entry(k,++nextRevision));
        var ready=new HashSet<TileKey>();
        for(var e:entries.values()) if(e.resident!=null) ready.add(e.key);
        var previous=visibleLevels(drawn,limits.minLodVisible(),limits.maxLodVisible());
        var visible=resolveTransitions(previous,target,ready,visibleLevels(plan.deferred(),limits.minLodVisible(),limits.maxLodVisible()));
        var keep=new HashSet<TileKey>(demand); keep.addAll(visible);
        // Distance changes only change demand. Keep completed meshes as an idle cache
        // until byte-budget pressure evicts them, so shrinking then growing reuses work.
        for(var e:entries.values()) if(e.resident!=null) keep.add(e.key);
        removeEntries(e -> !keep.contains(e.key));
        wanted=target; requested=Set.copyOf(demand);
        if(!drawn.equals(visible))drawn=Set.copyOf(visible);
    }
    /** Only primary targets spawn variants; equal footprint, no recursive prefetch. */
    public static Set<TileKey> adaptiveVariants(Set<TileKey> targets,int min,int max) { return adaptiveVariants(targets,min,max,6); }
    public static Set<TileKey> adaptiveVariants(Set<TileKey> targets,int min,int max,int limit) {
        var variants=new HashSet<TileKey>();
        for(var key:targets) {
            int lod=Integer.numberOfTrailingZeros(key.spacing());if(lod>limit)continue;
            for(int adjacent:new int[]{lod-1,lod+1}) if(adjacent>=Math.max(0,min)&&adjacent<=Math.min(18,max) && (1L<<adjacent)<=key.blocks())
                variants.add(new TileKey(key.x(),key.z(),1<<adjacent,key.sizeSpacing()));
        }
        variants.removeAll(targets);return Set.copyOf(variants);
    }
    private static TileKey parent(TileKey key) {
        if(key.sizeSpacing()>=dev.faraway.k7q4m9.terrain.WorldGrid.SPACING) return null;
        int size=key.sizeSpacing()*2;
        return new TileKey(Math.floorDiv(key.x(),2),Math.floorDiv(key.z(),2),size,size);
    }
    private static TileKey region(TileKey key) { return new TileKey(key.x(),key.z(),key.sizeSpacing(),key.sizeSpacing()); }
    /** Commit overlapping replacement tiles together only after all are resident. */
    public static Set<TileKey> resolveTransitions(Set<TileKey> previous,Set<TileKey> target,Set<TileKey> resident,Set<TileKey> deferred) {
        var old=new HashSet<TileKey>(previous); old.retainAll(resident); old.removeAll(target);
        var missing=new HashSet<TileKey>(target); missing.removeAll(resident); missing.addAll(deferred);
        var missingAncestors=ancestors(missing);
        var missingRegions=regions(missing);
        var held=new HashSet<TileKey>();
        for(var key:old) if(overlaps(key,missingRegions,missingAncestors)) held.add(key);
        var ready=new HashSet<TileKey>(target); ready.retainAll(resident);
        Set<TileKey> withheld;
        boolean changed;
        do {
            var heldAncestors=ancestors(held);
            var heldRegions=regions(held);
            withheld=new HashSet<>();
            for(var key:ready) if(overlaps(key,heldRegions,heldAncestors)) withheld.add(key);
            var withheldAncestors=ancestors(withheld);
            var withheldRegions=regions(withheld);
            changed=false;
            for(var key:old) if(!held.contains(key) && overlaps(key,withheldRegions,withheldAncestors)) changed|=held.add(key);
        } while(changed);
        ready.removeAll(withheld); ready.addAll(held);
        return ready;
    }
    private static Set<TileKey> ancestors(Set<TileKey> keys) {
        var result=new HashSet<TileKey>();
        for(var key:keys) for(TileKey k=region(key);k!=null;k=parent(k)) result.add(k);
        return result;
    }
    private static boolean overlaps(TileKey key,Set<TileKey> keys,Set<TileKey> keyAncestors) {
        if(keyAncestors.contains(region(key))) return true;
        for(TileKey k=region(key);k!=null;k=parent(k)) if(keys.contains(k)) return true;
        return false;
    }
    private static Set<TileKey> regions(Set<TileKey> keys) { var result=new HashSet<TileKey>(); for(var key:keys) result.add(region(key)); return result; }
    private boolean resident(TileKey key) { var e=entries.get(key); return e!=null && e.resident!=null; }
    public int[][] lodProgress(int minLevel,int maxLevel) {
        int[][] progress=new int[19][2];
        for(var key:wanted) {
            int level=Integer.numberOfTrailingZeros(key.spacing());
            if(level<minLevel || level>maxLevel || level>=progress.length) continue;
            progress[level][0]++;
            if(resident(key)) progress[level][1]++;
        }
        return progress;
    }
    public Set<TileKey> displayedTiles() {
        if(TileKey.WORLD)return drawn;
        var visible=new HashSet<TileKey>();for(var key:wanted)if(resident(key))visible.add(key);return Set.copyOf(visible);
    }
    public boolean drawable(TileKey key) { return !TileKey.WORLD || drawn.contains(key); }
    public java.util.List<TileKey> missingPreviewTiles() {
        var result=new java.util.ArrayList<TileKey>();
        for(var key:wanted) if(!resident(key)) result.add(key);
        return result;
    }
    private double priority(Entry entry,long now) {
        var key=entry.key;
        double dx=Math.max(0,Math.max(key.originX()-cameraX,cameraX-((double)key.originX()+key.blocks())));
        double dz=Math.max(0,Math.max(key.originZ()-cameraZ,cameraZ-((double)key.originZ()+key.blocks())));
        double dy=Math.max(0,Math.max(world.heights().getMinY()-cameraY,cameraY-world.heights().getMaxY()));
        return dx*dx+dy*dy+dz*dz;
    }
    public static boolean inVisibleRange(TileKey key,int minLevel,int maxLevel) {
        int level=Integer.numberOfTrailingZeros(key.spacing());
        return level>=minLevel && level<=maxLevel;
    }
    public static Set<TileKey> visibleLevels(Set<TileKey> keys,int minLevel,int maxLevel) {
        var result=new HashSet<TileKey>();
        for(var key:keys) if(inVisibleRange(key,minLevel,maxLevel)) result.add(key);
        return result;
    }
    private boolean current(Entry entry) {
        var settings=dev.faraway.k7q4m9.client.LodSettings.get();
        return !closed && entries.get(entry.key)==entry && inVisibleRange(entry.key,settings.minLodVisible(),settings.maxLodVisible());
    }
    private void removeEntries(java.util.function.Predicate<Entry> predicate) {
        synchronized(publicationLock) {
            for(var entry:entries.values()) if(predicate.test(entry) && entries.remove(entry.key,entry)) {
                if(entry.resident!=null) entry.resident.close();
            }
        }
    }
    private boolean workAllowed(Entry entry) { return current(entry) && LodSettings.get().generationEnabled(); }
    private void generate(Entry entry,ByteBudget.Reservation reservation,long queuedAt,PartialTile partial,LodSettings.GenerationOptions options) {
        boolean handedOff=false;
        Parked next=null;
        SurfaceSamples savedSurface=null;
        var profile=partial==null?WorldgenProfiler.tile(entry.key,entry.created,queuedAt):partial.profile;
        var profileScope=WorldgenProfiler.scope(profile,Phase.OTHER);
        try {
            if(!workAllowed(entry) || partial!=null && !poolAllowed(partial)) return;
            entry.state=State.SAMPLING;
            java.util.Arrays.fill(entry.decorationPhases,0);
            var sampler=workerSampler.get();
            long start=partial==null?System.nanoTime():partial.started;
            sampler.prepare(partial==null?adaptStructures(entry):partial.adapted,options);
            TileMesh mesh,preparedDecorations=null;
            long sampled,meshStarted;
            int sampledMaximum=0,sampledSea=0;
            boolean generatedPlane=false;
            if(useVoxelMesh(entry.generatePlanes) || !entry.generateVoxels) {
                var samples=partial!=null?partial.samples:entry.resident!=null && entry.resident.samples!=null
                    && entry.generateStructures==entry.resident.structures?entry.resident.samples:dev.faraway.k7q4m9.terrain.VoxelTile.sampleSurface(entry.key,sampler,() -> !workAllowed(entry));
                sampled=partial==null?System.nanoTime():partial.sampledAt;
                meshStarted=System.nanoTime();
                sampledSea=sampler.seaLevel();
                try(var phase=WorldgenProfiler.scope(Phase.HEIGHTMAPS)) {
                    sampledMaximum=dev.faraway.k7q4m9.terrain.FarPlaneTile.maxSurfaceY(entry.key,sampledSea,samples);
                }
                generatedPlane=shouldUsePlane(entry.key,sampledSea,sampledMaximum,entry.threshold,entry.generatePlanes,entry.generateVoxels,voxel);
                if(partial!=null) {
                    // Resolve reusable decoration results before creating a mesh that would have to be parked.
                    if(!generatedPlane && decorationsNeeded(entry)) {
                        entry.state=State.STRUCTURES;
                        preparedDecorations=structures(entry,sampler,true);
                        meshStarted=System.nanoTime();
                    }
                    releasePartialSamples(partial);
                }
                entry.state=State.MESHING;
                if(generatedPlane) {
                    synchronized(publicationLock) {
                        if(!workAllowed(entry))throw new CancellationException();
                        if(entry.resident!=null && entry.resident.samples==samples && entry.resident.surface!=null) savedSurface=entry.resident.surface.retain();
                        else {
                            var lease=retainedSamples.tryReserve(Math.max(1,16L+48L*samples.length));
                            if(lease!=null)savedSurface=new SurfaceSamples(samples,lease);
                        }
                    }
                    // Retaining samples is a bounded cache. A full cache must not block publication.
                    try(var phase=WorldgenProfiler.scope(Phase.TERRAIN_MESH)) {
                        var tile=dev.faraway.k7q4m9.terrain.FarPlaneTile.fromSamples(entry.key,sampler,samples,entry.generateTrees,() -> !workAllowed(entry));
                        mesh=TileMesh.farPlane(tile);
                    }
                } else {
                    try(var phase=WorldgenProfiler.scope(Phase.TERRAIN_MESH)) {
                        var tile=dev.faraway.k7q4m9.terrain.VoxelTile.fromSamples(entry.key,sampler,() -> !workAllowed(entry),dev.faraway.k7q4m9.client.LodSettings.blendEnabled(entry.key.spacing()),samples);
                        mesh=new dev.faraway.k7q4m9.mesh.VoxelMeshBuilder().build(tile,() -> !workAllowed(entry));
                    }
                }
            } else {
                var tile=LodTile.generate(entry.key,sampler,() -> !workAllowed(entry));
                meshStarted=sampled=System.nanoTime(); entry.state=State.MESHING;
                try(var phase=WorldgenProfiler.scope(Phase.TERRAIN_MESH)) {
                    mesh=new TerrainMeshBuilder().build(tile,() -> !workAllowed(entry));
                }
            }
            // A tile is not publishable until every supported structure patch is placed
            // and included in the same mesh. No independent, delayed structure stream.
            long terrainMeshDone=System.nanoTime();
            if(preparedDecorations!=null || partial==null && mesh.farPlane()==null && decorationsNeeded(entry)) {
                entry.state=State.STRUCTURES;
                var decorations=preparedDecorations!=null?preparedDecorations:structures(entry,sampler,false);
                long appendStart=System.nanoTime();
                try(var phase=WorldgenProfiler.scope(Phase.DECORATION_MESH)) { mesh=mesh.append(decorations); }
                entry.decorationPhases[7]=System.nanoTime()-appendStart;
            }
            long beforeBytes=mesh.bytes(),beforeVertices=mesh.vertexCount()+mesh.waterVertexCount(),beforeTriangles=mesh.triangleCount();
            long optimizeStart=System.nanoTime();
            try(var phase=WorldgenProfiler.scope(Phase.OPTIMIZE)) {
                if(useVoxelMesh(entry.generatePlanes) && entry.packedQuadMesh)mesh=mesh.packQuads(() -> !workAllowed(entry));
                mesh=mesh.optimize(useVoxelMesh(entry.generatePlanes) && entry.key.spacing()>1,() -> !workAllowed(entry));
            }
            dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.debug("FarAway mesh metrics lod={} key={} beforeBytes={} beforeVertices={} triangles={} meshMs={} optimizedBytes={} vertices={} indexBytes={} format={} optimizeMs={}",
                Integer.numberOfTrailingZeros(entry.key.spacing()),entry.key,beforeBytes,beforeVertices,beforeTriangles,(optimizeStart-sampled)/1_000_000,
                mesh.bytes(),mesh.vertexCount()+mesh.waterVertexCount(),mesh.indices().remaining(),mesh.quadCount()>0?"quad40":mesh.vertexStride()==8?"grid8":"precise16",(System.nanoTime()-optimizeStart)/1_000_000);
            samplingNanos.add(sampled-start); meshingNanos.add(System.nanoTime()-sampled); sampledTiles.increment();
            long finished=System.nanoTime();
            long meshNanos=terrainMeshDone-meshStarted,decorationNanos=finished-sampled-meshNanos;
            if(profile!=null) profile.add(Phase.GENERATION,finished-start);
            double total=Math.max(1,finished-start);
            dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.debug("FarAway tile timing key={} totalMs={} surfaceMs={} surfacePct={} terrainMeshMs={} terrainMeshPct={} structuresTreesAndMeshMs={} structuresTreesAndMeshPct={} bytes={}",entry.key,
                (finished-start)/1_000_000,(sampled-start)/1_000_000,Math.round(100*(sampled-start)/total),
                meshNanos/1_000_000,Math.round(100*meshNanos/total),
                decorationNanos/1_000_000,Math.round(100*decorationNanos/total),mesh.bytes());
            dev.faraway.k7q4m9.diagnostics.TileLogSummary.generated(entry.key,entry.decorationPhases,Integer.numberOfTrailingZeros(entry.key.spacing()),finished-start,sampled-start,meshNanos,decorationNanos,finished-optimizeStart,beforeBytes,mesh.bytes(),beforeVertices,mesh.vertexCount()+mesh.waterVertexCount(),beforeTriangles,mesh.indices().remaining());
            if(mesh.heapBytes()>reservation.bytes() && !reservation.tryGrow(mesh.heapBytes())) throw new IllegalStateException("Terrain and structures exceed the pending-result byte budget");
            synchronized(publicationLock) {
                if(current(entry)) {
                    entry.state=State.UPLOAD_PENDING;
                    profileScope.close();
                    long meshReady=profile==null?0:System.nanoTime();
                    completed.add(new Completed(entry,mesh,reservation,finished-start,new Resident(generatedPlane,!generatedPlane && useVoxelMesh(entry.generatePlanes),entry.generateStructures,entry.generateTrees,entry.opaqueLeaves,sampledSea,sampledMaximum,savedSurface),profile,meshReady));
                    savedSurface=null;
                    handedOff=true;
                }
            }
        } catch(CachePause.Pending pending) {
            next=new Parked(pending.result());
        } catch(CancellationException cancelled) {
            cancelledTiles.increment();
            // Entry identity invalidates queued, in-flight, and completed work together.
        } catch(Exception failure) {
            profileScope.close();
            generationFailed(entry,profile,failure);
        } finally {
            profileScope.close();
            if(savedSurface!=null) savedSurface.close();
            if(!handedOff) {
                reservation.close();
                synchronized(publicationLock) {
                    if(next!=null && partial!=null && poolAllowed(partial)) parkPartial(partial,next);
                    else {
                        if(partial!=null) releasePartialSamples(partial);
                        if(profile!=null) profile.cancelled();
                        if(current(entry)) entry.state=State.MISSING;
                    }
                }
            }
            inFlight.decrementAndGet();
        }
    }

    private void generationFailed(Entry entry,WorldgenProfiler.Work profile,Exception failure) {
        if(profile!=null) profile.failed();
        entry.failures++;
        entry.error=failure.toString();
        entry.retryAt=System.nanoTime()+Math.min(30,1L<<entry.failures)*1_000_000_000L;
        entry.state=State.MISSING;
        dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.error("Terrain tile {} failed",entry.key,failure);
    }

    private boolean decorationsNeeded(Entry entry) {
        return world.detachedDecorations() && (entry.generateTrees || entry.generateStructures && supportsStructures(entry.key))
            && DirectLodPlanner.intersects(entry.key,cameraX,cameraZ,structureRadius);
    }

    private TileMesh structures(Entry entry,WorldTerrainSampler terrain,boolean resumable) {
        long decorationStart=System.nanoTime();
        boolean includeStructures=entry.generateStructures && supportsStructures(entry.key);
        boolean exactTrees=entry.generateTrees && entry.key.spacing()==1;
        var sampler=includeStructures || exactTrees?structureSampler.get():null;
        int x=(int)cameraX,z=(int)cameraZ,radius=structureRadius;
        var blocks=new java.util.HashMap<net.minecraft.core.BlockPos,net.minecraft.world.level.block.state.BlockState>();
        Set<TileKey> patches;
        try(var ignored=CachePause.allow(resumable)) {
            patches=includeStructures?WorldgenProfiler.measure(Phase.STRUCTURE_STARTS,() -> sampler.patches(entry.key)):Set.of();
        }
        long indexDone=System.nanoTime(),generatedNs=0,waitNs=0,hitNs=0,filterNs=0; int selected=0,blockCount=0,generatedCount=0,waitCount=0,hitCount=0;
        int minX=Math.max(entry.key.originX(),x-radius-32),minZ=Math.max(entry.key.originZ(),z-radius-32);
        int maxX=(int)Math.min((long)entry.key.originX()+entry.key.blocks()-1,(long)x+radius+32);
        int maxZ=(int)Math.min((long)entry.key.originZ()+entry.key.blocks()-1,(long)z+radius+32);
        for(var patch:patches) {
            if(!workAllowed(entry)) throw new CancellationException();
            if(!DirectLodPlanner.intersects(patch,x,z,radius)
                || patch.originX()+32<=entry.key.originX() || patch.originZ()+32<=entry.key.originZ()
                || patch.originX()>=entry.key.originX()+entry.key.blocks() || patch.originZ()>=entry.key.originZ()+entry.key.blocks()) continue;
            selected++;
            long cacheLimit=Math.max(1,dev.faraway.k7q4m9.client.LodSettings.toBytes(dev.faraway.k7q4m9.client.LodSettings.effectiveHeapGiB())/8);
            dev.faraway.k7q4m9.worldgen.DecorationCache.TimedResult timed;
            for(;;) {
                try(var ignored=CachePause.allow(resumable)) {
                    timed=decorationCache.get(patch,true,false,terrain.scratchTerrainMode(),cacheLimit,
                        () -> sampler.generateStructures(patch,() -> !workAllowed(entry)));
                    break;
                }
                catch(CancellationException cancelled) { if(!workAllowed(entry)) throw cancelled; }
            }
            switch(timed.source()) {
                case GENERATED -> { generatedNs+=timed.nanos(); generatedCount++; }
                case HIT -> { hitNs+=timed.nanos(); hitCount++; }
                case WAITED -> { waitNs+=timed.nanos(); waitCount++; }
            }
            long phase=System.nanoTime();
            var waterTops=new int[32*32];
            java.util.Arrays.fill(waterTops,Integer.MIN_VALUE);
            try(var profilePhase=WorldgenProfiler.scope(Phase.STRUCTURE_FILTER)) {
                for(var block:timed.result().blocks().entrySet()) {
                    blockCount++;
                    if(dev.faraway.k7q4m9.mesh.DecorationMeshBuilder.skipUnsupportedDecoration(block.getValue())) continue;
                    var p=block.getKey();
                    if(p.getX()<entry.key.originX() || p.getX()>=entry.key.originX()+entry.key.blocks()
                        || p.getZ()<entry.key.originZ() || p.getZ()>=entry.key.originZ()+entry.key.blocks()) continue;
                    int column=(p.getZ()-patch.originZ())*32+p.getX()-patch.originX();
                    int waterTop=waterTops[column];
                    if(waterTop==Integer.MIN_VALUE) {
                        var surface=terrain.placementSurface(p.getX(),p.getZ());
                        waterTops[column]=waterTop=surface.water()?surface.waterTopY():Integer.MIN_VALUE+1;
                    }
                    if(p.getY()+1<=waterTop) continue;
                    blocks.put(p,block.getValue());
                }
            }
            filterNs+=System.nanoTime()-phase;
        }
        if(exactTrees) {
            long vegetationStart=System.nanoTime();
            try(var phase=WorldgenProfiler.scope(Phase.TREES)) {
                exactTreeBlocks(entry,sampler,terrain.scratchTerrainMode(),includeStructures,minX,minZ,maxX,maxZ,resumable).forEach(blocks::putIfAbsent);
            }
            entry.decorationPhases[6]=System.nanoTime()-vegetationStart;
        }
        entry.state=State.MESHING;
        long meshStart=System.nanoTime();
        var foliage=(java.util.function.IntBinaryOperator)(bx,bz) -> terrain.sample(bx,bz,entry.key.spacing()).foliageColor();
        var structures=WorldgenProfiler.measure(Phase.STRUCTURE_MESH,() -> useVoxelMesh(entry.generatePlanes) && entry.key.spacing()>1
            ?new dev.faraway.k7q4m9.mesh.GridStructureMeshBuilder().build(entry.key,blocks,foliage,() -> !workAllowed(entry))
            :new dev.faraway.k7q4m9.mesh.DecorationMeshBuilder().build(entry.key,blocks,foliage,entry.opaqueLeaves,() -> !workAllowed(entry)));
        long structuresDone=System.nanoTime();
        dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.debug("FarAway structure detail key={} indexUs={} patchGenerateUs={} patchWaitUs={} patchHitUs={} filterSurfaceUs={} structureMeshUs={} patches={} generated={} waited={} hits={} inputBlocks={} keptBlocks={} structureBytes={}",entry.key,
            (indexDone-decorationStart)/1000,generatedNs/1000,waitNs/1000,hitNs/1000,filterNs/1000,(structuresDone-meshStart)/1000,selected,generatedCount,waitCount,hitCount,blockCount,blocks.size(),structures.bytes());
        entry.decorationPhases[0]=indexDone-decorationStart;
        entry.decorationPhases[1]=generatedNs;entry.decorationPhases[2]=waitNs;entry.decorationPhases[3]=hitNs;
        entry.decorationPhases[4]=filterNs;entry.decorationPhases[5]=structuresDone-meshStart;
        var result=structures;
        if(entry.generateTrees && !exactTrees) {
            long vegetationStart=System.nanoTime();
            var vegetation=new dev.faraway.k7q4m9.mesh.ApproxTreeMeshBuilder();
            try(var phase=WorldgenProfiler.scope(Phase.TREES)) {
                result=structures.append(vegetation.build(entry.key,world,terrain,x,z,radius,() -> !workAllowed(entry)));
            }
            entry.decorationPhases[6]=System.nanoTime()-vegetationStart;
            var stats=vegetation.stats();
            dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.debug("FarAway vegetation timing key={} lod={} generationUs={} meshUs={} surfaceSamples={} candidates={} voxels={} triangles={}",entry.key,
                Integer.numberOfTrailingZeros(entry.key.spacing()),stats.generationNs()/1000,stats.meshNs()/1000,stats.surfaceSamples(),stats.candidates(),stats.voxels(),stats.triangles());
        }
        dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.debug("FarAway decoration timing key={} exactTrees={} structuresMs={} approxTreesMs={} bytes={}",entry.key,exactTrees,
            (structuresDone-decorationStart)/1_000_000,(System.nanoTime()-structuresDone)/1_000_000,result.bytes());
        return result;
    }
    private java.util.Map<BlockPos,BlockState> exactTreeBlocks(Entry entry,DetachedDecorationSampler sampler,LodSettings.ScratchTerrain scratch,
                                                          boolean structures,int minX,int minZ,int maxX,int maxZ,boolean resumable) {
        var trees=new HashMap<BlockPos,BlockState>();
        if(minX>maxX || minZ>maxZ) return trees;
        var existing=existingTrees.capture(minX,minZ,maxX,maxZ,() -> !workAllowed(entry));
        int size=DetachedDecorationSampler.TREE_REGION_BLOCKS,margin=DetachedDecorationSampler.TREE_WRITE_MARGIN;
        long limit=Math.max(1,LodSettings.toBytes(LodSettings.effectiveHeapGiB())/8);
        if(!existing.covers(minX,minZ,maxX,maxZ)) {
            for(int rz=Math.floorDiv(minZ-margin,size);rz<=Math.floorDiv(maxZ+margin,size);rz++)
                for(int rx=Math.floorDiv(minX-margin,size);rx<=Math.floorDiv(maxX+margin,size);rx++) {
                    if(!workAllowed(entry)) throw new CancellationException();
                    var region=new TileKey(rx,rz,1,size/TileKey.CELLS);
                    if(existing.covers(Math.max(minX,region.originX()-margin),Math.max(minZ,region.originZ()-margin),
                        Math.min(maxX,region.originX()+size+margin-1),Math.min(maxZ,region.originZ()+size+margin-1))) continue;
                    // The region is shared by several tiles. A camera replan can
                    // replace its first requester without invalidating the result.
                    DetachedDecorationSampler.Result result;
                    try(var ignored=CachePause.allow(resumable)) {
                        result=decorationCache.get(region,structures,true,scratch,limit,
                            () -> sampler.generateTreeRegion(region,structures,() -> closed || !LodSettings.get().generationEnabled())).result();
                    }
                    if(!workAllowed(entry)) throw new CancellationException();
                    for(var block:result.blocks().entrySet()) {
                        var pos=block.getKey();
                        if(pos.getX()<minX || pos.getX()>maxX || pos.getZ()<minZ || pos.getZ()>maxZ
                            || existing.contains(pos)) continue;
                        trees.put(pos,block.getValue());
                    }
                }
        }
        existing.addTo(trees,minX,minZ,maxX,maxZ,() -> !workAllowed(entry));
        return trees;
    }

    /** Structure placement is enabled through LOD 3 (8 blocks per sample). */
    public static boolean supportsStructures(TileKey key) { return key.spacing()<=8; }

    public Completed pollCompleted() {
        Completed result;
        while((result=completed.poll())!=null) {
            if(current(result.entry)) return result;
            result.close();
        }
        return null;
    }
    public Set<TileKey> wanted() { return wanted; }
    public boolean retained(TileKey key) { return entries.containsKey(key); }
    /** Pressure is sticky for this candidate: never regenerate and evict the same prefetch loop. */
    public boolean trimToWanted() {
        boolean changed=prefetchEnabled || entries.keySet().stream().anyMatch(key -> !wanted.contains(key) && !drawn.contains(key));
        prefetchEnabled=false; requested=wanted;
        removeEntries(e -> !wanted.contains(e.key) && !drawn.contains(e.key));
        return changed;
    }
    public int retainedCount() { return entries.size(); }
    public long pendingBytes() { return outputs.used(); }
    /** Completed CPU meshes still queued for upload; active builds have no final triangle count. */
    public long queuedTriangles() { long count=0; for(var item:completed) if(item.isCurrent()) count+=item.mesh().triangleCount(); return count; }
    public void recordQueuedLodTimings(LodTimingStats timings,Set<TileKey> counted) {
        for(var item:completed) item.recordLodTiming(timings,counted);
    }
    public long pendingPeak() { return outputs.peak(); }
    public boolean prefetchReady() {
        for(TileKey key:requested) { var e=entries.get(key); if(e==null || e.state!=State.RESIDENT) return false; }
        return !requested.isEmpty();
    }
    public int residentCount() { return (int)entries.values().stream().filter(e -> wanted.contains(e.key) && e.resident!=null).count(); }
    public boolean benchmarkCoverageReady() { return !budgetLimited && !wanted.isEmpty() && residentCount()==wanted.size() && failures()==0 && wanted.stream().allMatch(k -> { var e=entries.get(k); return e!=null && e.state==State.RESIDENT; }); }
    public boolean benchmarkPlanTooLarge() { return benchmarkPlanLocked && budgetLimited; }
    public int failures() { return (int)entries.values().stream().filter(e -> wanted.contains(e.key) && e.failures>=3).count(); }
    public long oldestMissingNanos() {
        long now=System.nanoTime(),oldest=0;
        for(Entry e:entries.values()) if(wanted.contains(e.key) && e.state!=State.RESIDENT) oldest=Math.max(oldest,now-e.created);
        return oldest;
    }
    @Override public void close() {
        synchronized(publicationLock) {
            closed=true; planning.close(); removeEntries(e -> true); wanted=Set.of(); requested=Set.of();
            var iterator=partialTiles.values().iterator();
            while(iterator.hasNext()) {
                var partial=iterator.next();
                if(partial.pause!=null) {
                    iterator.remove();
                    discardPartial(partial);
                }
            }
            decorationCache.clear();
            if(existingTrees!=null) existingTrees.clear();
            terrainCache.clear();
            Completed item; while((item=completed.poll())!=null) item.close();
        }
        // Queued jobs must still execute their cancellation/finally path to release reservations.
        executor.shutdown();
    }

    public final class Completed implements AutoCloseable {
        private final Entry entry;
        private final TileMesh mesh;
        private final ByteBudget.Reservation reservation;
        private final long generationNanos;
        private final Resident nextResident;
        private final WorldgenProfiler.Work profile;
        private final long meshReady;
        private boolean acknowledged;
        private boolean released;
        private Completed(Entry entry,TileMesh mesh,ByteBudget.Reservation reservation,long generationNanos,Resident nextResident,WorldgenProfiler.Work profile,long meshReady) {
            this.entry=entry; this.mesh=mesh; this.reservation=reservation; this.generationNanos=generationNanos; this.nextResident=nextResident;
            this.profile=profile; this.meshReady=meshReady;
        }
        public TileMesh mesh() { return mesh; }
        public long generationNanos() { return generationNanos; }
        public void recordLodTiming(LodTimingStats timings,Set<TileKey> counted) {
            if(isCurrent() && counted.add(mesh.key())) timings.record(mesh.key(),generationNanos);
        }
        public long revision() { return entry.revision; }
        public boolean isCurrent() { return !released && current(entry); }
        public long uploadStarted() { return profile==null?0:System.nanoTime(); }
        public void uploaded(long uploadStart) {
            if(!isCurrent()) return;
            var old=entry.resident;
            entry.resident=nextResident;
            if(old!=null) old.close();
            entry.generationNanos=generationNanos;
            entry.state=State.RESIDENT;
            acknowledged=true;
            if(profile!=null) profile.ready(meshReady,uploadStart);
        }
        public void uploadFailed(Exception failure) {
            if(profile!=null) profile.failed();
            if(isCurrent()) {
                entry.error=failure.toString(); entry.failures++;
                entry.retryAt=System.nanoTime()+2_000_000_000L;
            }
        }
        public void uploadDeferred() { if(isCurrent()) entry.retryAt=System.nanoTime()+1_000_000_000L; }
        @Override public void close() {
            if(released) return;
            released=true;
            if(!acknowledged && current(entry)) entry.state=State.MISSING;
            if(!acknowledged) nextResident.close();
            if(!acknowledged && profile!=null) profile.cancelled();
            reservation.close();
        }
    }
    private enum State { MISSING,QUEUED,SAMPLING,WAITING,STRUCTURES,MESHING,UPLOAD_PENDING,RESIDENT }

    private static final class Parked {
        final long since=System.nanoTime();
        volatile long readyAt;

        Parked(CompletableFuture<?> dependency) {
            if(dependency==null) readyAt=since;
            else dependency.whenComplete((result,failure) -> readyAt=System.nanoTime());
        }
    }

    private static final class PartialTile {
        final Entry entry;
        final LodSettings.GenerationOptions options;
        final boolean adapted;
        final long bytes;
        SurfaceSample[] samples;
        int nextSample;
        long started,sampledAt;
        WorldgenProfiler.Work profile;
        Parked pause;

        PartialTile(Entry entry,LodSettings.GenerationOptions options,boolean adapted,long bytes) {
            this.entry=entry;
            this.options=options;
            this.adapted=adapted;
            this.bytes=bytes;
        }

        boolean surfaceReady() { return samples!=null && nextSample==samples.length; }

        void chargePause(Parked parked) {
            if(parked==null || profile==null) return;
            long now=System.nanoTime(),ready=parked.readyAt;
            profile.add(Phase.POOL_WAIT,(ready==0?now:ready)-parked.since);
            if(ready!=0) profile.add(Phase.POOL_READY_QUEUE,now-ready);
        }
    }
    private static final class SurfaceSamples implements AutoCloseable {
        final SurfaceSample[] samples;final ByteBudget.Reservation lease;
        final java.util.concurrent.atomic.AtomicInteger references=new java.util.concurrent.atomic.AtomicInteger(1);
        SurfaceSamples(SurfaceSample[] samples,ByteBudget.Reservation lease){this.samples=samples;this.lease=lease;}
        SurfaceSamples retain(){references.incrementAndGet();return this;}
        public void close(){if(references.decrementAndGet()==0)lease.close();}
    }
    private static final class Resident implements AutoCloseable {
        final boolean plane,voxelMesh,structures,trees,opaqueLeaves;final int seaLevel,maxY;final SurfaceSample[] samples;final SurfaceSamples surface;
        Resident(boolean plane,boolean voxelMesh,boolean structures,boolean trees,boolean opaqueLeaves,int seaLevel,int maxY,SurfaceSamples surface){this.plane=plane;this.voxelMesh=voxelMesh;this.structures=structures;this.trees=trees;this.opaqueLeaves=opaqueLeaves;this.seaLevel=seaLevel;this.maxY=maxY;this.surface=surface;this.samples=surface==null?null:surface.samples;}
        public void close(){if(surface!=null)surface.close();}
    }
    private static final class Entry {
        final TileKey key;
        final long[] decorationPhases=new long[8];
        final long revision,created=System.nanoTime();
        volatile State state=State.MISSING;
        volatile int failures;
        volatile long retryAt;
        volatile long lastUsedNanos=System.nanoTime();
        volatile long generationNanos=-1;
        volatile String error;
        final boolean generatePlanes=LodSettings.get().generatePlaneTiles(),generateVoxels=LodSettings.get().generateVoxelTiles();
        final boolean generateStructures=LodSettings.get().generateStructures(),generateTrees=LodSettings.get().generateTrees();
        final boolean packedQuadMesh=LodSettings.get().packedQuadMesh();
        final boolean opaqueLeaves=LodSettings.get().opaqueLeaves();
        final int threshold;
        volatile Resident resident;
        Entry(TileKey key,long revision) { this(key,revision,LodSettings.get().planeThreshold()); }
        Entry(TileKey key,long revision,int threshold) { this.key=key; this.revision=revision; this.threshold=threshold; }
    }
}
