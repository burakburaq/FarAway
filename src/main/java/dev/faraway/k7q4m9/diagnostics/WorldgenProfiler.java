package dev.faraway.k7q4m9.diagnostics;

import dev.faraway.k7q4m9.client.FarAwayClient;
import dev.faraway.k7q4m9.terrain.TileKey;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Opt-in timings. Scoped work is exclusive; latency and wait rows are separate. */
public final class WorldgenProfiler {
    public enum Phase {
        TOTAL, ACTIVE, GENERATION, REQUEST_QUEUE, WORKER_QUEUE, STAGE_WAIT, DISK_LOAD, LIGHT_WAIT, GPU_WAIT, CACHE_WAIT, POOL_WAIT, POOL_READY_QUEUE, UPLOAD_QUEUE,
        NOISE, SURFACE, SCRATCH_TERRAIN, LOADED_SURFACE, CARVERS, BIOMES, BIOME_COLOR, ORES, ORE_VEINS, TREES, TREE_CANDIDATES,
        TREE_LIVE_READ, TREE_DISK_READ, VEGETATION, LAKES, STRUCTURE_STARTS, STRUCTURE_REFERENCES,
        STRUCTURE_PLACEMENT, STRUCTURE_FILTER, STRUCTURE_MESH, HEIGHTMAPS, LIGHT_PREP, LIGHT_PROPAGATE, LIGHT_BATCH,
        SPAWN, FINALIZE, TERRAIN_MESH, DECORATION_MESH, OPTIMIZE, UPLOAD, CACHE_HIT, OTHER_FEATURES, OTHER;

        boolean latency() { return ordinal()<=UPLOAD_QUEUE.ordinal() || this==TREE_LIVE_READ || this==TREE_DISK_READ; }
    }
    private static final int PHASES=Phase.values().length;
    private static final long WINDOW=10_000_000_000L, PARTIAL_TTL=30_000_000_000L;
    private static final int MAX_PENDING=8192, SLOWEST=8;
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    private static volatile Session current;

    private WorldgenProfiler() {}

    public static boolean enabled() { return current!=null; }

    public static void setEnabled(boolean enabled) {
        if(enabled==enabled()) return;
        if(enabled) {
            current=new Session();
            FarAwayClient.LOGGER.info("FarAway worldgen profiling started");
        } else stop();
    }

    public static void stop() {
        var session=current;
        current=null;
        if(session==null) return;
        var report=session.finish();
        Thread.ofPlatform().name("FarAway worldgen report").start(() -> {
            try {
                var path=FabricLoader.getInstance().getGameDir().resolve("logs")
                    .resolve("faraway-worldgen-"+Instant.now().toString().replace(':','-')+".tsv");
                Files.createDirectories(path.getParent());
                Files.writeString(path,report);
                FarAwayClient.LOGGER.info("FarAway worldgen profiling report {}",path.toAbsolutePath());
            } catch(Exception failure) {
                FarAwayClient.LOGGER.error("Could not write FarAway worldgen profile",failure);
            }
        });
    }

    public static void poll() {
        var session=current;
        if(session!=null) session.flush(System.nanoTime());
    }

    public static Work tile(TileKey key,long requested,long queued) {
        var session=current;
        if(session==null) return null;
        int lod=Integer.numberOfTrailingZeros(key.spacing()),sot=Integer.numberOfTrailingZeros(key.sizeSpacing());
        long now=System.nanoTime();
        var work=new Work(session,"tile/LOD"+lod+"/SOT"+sot,key.originX()+","+key.originZ(),Math.max(requested,session.started));
        long admitted=Math.max(work.started,queued==0?now:queued);
        work.add(Phase.REQUEST_QUEUE,admitted-work.started);
        work.add(Phase.WORKER_QUEUE,now-admitted);
        return session.registerTile(work)?work:null;
    }

    public static Work chunk(ServerLevel level,ChunkPos pos) {
        var session=current;
        return session==null?null:session.chunk(level,pos);
    }

    public static Work chunk(ChunkAccess chunk) {
        var session=current;
        return session==null?null:session.running.get(chunk);
    }

    public static void startStage(ChunkAccess chunk,Work work,long now) {
        work.inStage=true;
        work.add(Phase.REQUEST_QUEUE,now-work.lastStage);
        work.session.running.put(chunk,work);
    }

    public static void endStage(ChunkAccess chunk,Work work,ChunkStatus status,long start,long activeBefore,Throwable failure) {
        work.session.running.remove(chunk,work);
        work.lastStage=System.nanoTime();
        work.inStage=false;
        long elapsed=System.nanoTime()-start;
        long wait=Math.max(0,elapsed-(work.active()-activeBefore));
        if(status==ChunkStatus.INITIALIZE_LIGHT || status==ChunkStatus.LIGHT) work.add(Phase.LIGHT_WAIT,wait);
        else work.add(Phase.STAGE_WAIT,wait);
        if(failure!=null || status==ChunkStatus.FULL) work.session.finishChunk(work,failure!=null);
    }

    public static void loaded(Work work,ChunkAccess result,long start,Throwable failure) {
        work.lastStage=System.nanoTime();
        work.inStage=false;
        work.add(Phase.DISK_LOAD,work.lastStage-start);
        if(failure!=null) work.session.finishChunk(work,true);
        else if(result!=null && result.getPersistedStatus().isOrAfter(ChunkStatus.FULL)) work.disk=true;
    }

    public static void startLoad(Work work,long now) {
        work.inStage=true;
        work.add(Phase.REQUEST_QUEUE,now-work.lastStage);
    }

    public static void lightBatch(long nanos) {
        var session=current;
        if(session==null) return;
        var work=new Work(session,"vanilla/light-batch","batch",System.nanoTime()-nanos);
        work.add(Phase.LIGHT_BATCH,nanos);
        work.complete();
    }

    public static Work activeWork() {
        var session=current;
        if(session==null) return null;
        var scope=CURRENT.get();
        return scope==null || scope.work.session!=session?null:scope.work;
    }

    public static boolean measuring() { return activeWork()!=null; }

    public static Scope scope(Phase phase) {
        if(current==null) return Scope.NONE;
        var parent=CURRENT.get();
        return parent==null || phase==null?Scope.NONE:scope(parent.work,phase);
    }

    public static Scope scope(Work work,Phase phase) {
        return work==null || work.session!=current?Scope.NONE:new Scope(work,phase);
    }

    public static <T> T measure(Phase phase,java.util.function.Supplier<T> action) {
        try(var scope=scope(phase)) { return action.get(); }
    }

    public static <T> T await(java.util.function.Supplier<T> action) {
        var work=activeWork();
        if(work==null) return action.get();
        long activeBefore=work.active(),start=System.nanoTime();
        try(var scope=scope(work,null)) { return action.get(); }
        finally { work.add(Phase.STAGE_WAIT,Math.max(0,System.nanoTime()-start-(work.active()-activeBefore))); }
    }

    /** Batch rule timings into the current worker's scope rather than locking per voxel. */
    public static void oreVein(long nanos) {
        var scope=CURRENT.get();
        if(scope==null) return;
        scope.children+=nanos;
        scope.oreVeins+=nanos;
    }

    public static final class Scope implements AutoCloseable {
        private static final Scope NONE=new Scope();
        private final Work work;
        private final Scope parent;
        private final Phase phase;
        private final long start;
        private long children,oreVeins;
        private boolean closed;

        private Scope() { work=null;parent=null;phase=null;start=0; }

        private Scope(Work work,Phase phase) {
            this.work=work;this.phase=phase;
            parent=CURRENT.get();
            start=System.nanoTime();
            CURRENT.set(this);
        }

        @Override public void close() {
            if(work==null || closed) return;
            closed=true;
            long elapsed=System.nanoTime()-start;
            if(parent==null) CURRENT.remove();
            else {
                CURRENT.set(parent);
                if(parent.work==work) parent.children+=elapsed;
            }
            if(phase!=null) work.add(phase,Math.max(0,elapsed-children));
            if(oreVeins>0) work.add(Phase.ORE_VEINS,oreVeins);
        }
    }

    public static final class Work {
        private final Session session;
        private final String group,location;
        private final WorldgenCacheProfile.Job cacheJob;
        private final long started;
        private final long[] nanos=new long[PHASES];
        private final BitSet seen=new BitSet(PHASES);
        private long active;
        private volatile long lastStage;
        private volatile boolean disk,inStage;
        private boolean finished;
        private int cacheWaitMask;
        private ChunkKey chunkKey;

        private Work(Session session,String group,String location,long started) {
            this.session=session;this.group=group;this.location=location;this.started=started;lastStage=started;
            cacheJob=session.cacheProfile.job(group,location);
        }

        WorldgenCacheProfile cacheProfile() { return session.cacheProfile; }
        WorldgenCacheProfile.Job cacheJob() { return cacheJob; }
        synchronized boolean markCacheWait(WorldgenCacheProfile.Kind kind) {
            int bit=1<<kind.ordinal();
            if((cacheWaitMask&bit)!=0) return false;
            cacheWaitMask|=bit;
            return true;
        }

        public synchronized long active() { return active; }

        public synchronized void add(Phase phase,long elapsed) {
            if(elapsed<0 || finished) return;
            int i=phase.ordinal();
            nanos[i]+=elapsed;
            seen.set(i);
            if(!phase.latency() && phase!=Phase.CACHE_HIT) active+=elapsed;
        }

        public void complete() { record(""); }
        public void cancelled() { record("/cancelled"); }
        public void failed() { record("/failed"); }
        public void partial() { record("/partial"); }
        public void ready(long meshReady,long uploadStart) {
            add(Phase.UPLOAD_QUEUE,uploadStart-meshReady);
            add(Phase.UPLOAD,System.nanoTime()-uploadStart);
            complete();
        }

        private void record(String suffix) {
            synchronized(this) {
                if(finished) return;
                add(Phase.TOTAL,System.nanoTime()-started);
                add(Phase.ACTIVE,active);
                finished=true;
            }
            session.record(this,group+(disk?"/disk":"")+suffix,location,nanos,seen,active);
        }
    }

    private record ChunkKey(ServerLevel level,long position) {}
    private record Slow(String where,long[] values,BitSet seen) {}

    private static final class Session {
        final long started=System.nanoTime();
        final WorldgenCacheProfile cacheProfile=new WorldgenCacheProfile(started);
        final Map<ChunkAccess,Work> running=new ConcurrentHashMap<>();
        final Map<ChunkKey,Work> pending=new LinkedHashMap<>();
        private final Set<Work> tiles=new HashSet<>();
        private final Map<String,Stats> total=new TreeMap<>(),window=new TreeMap<>();
        private long nextFlush=started+WINDOW;
        private boolean closed;

        synchronized Work chunk(ServerLevel level,ChunkPos pos) {
            if(closed) return null;
            var key=new ChunkKey(level,pos.pack());
            var work=pending.get(key);
            long now=System.nanoTime();
            if(work==null) {
                work=new Work(this,"vanilla/"+level.dimension().identifier(),pos.x()+","+pos.z(),now);
                work.chunkKey=key;
                pending.put(key,work);
                if(pending.size()>MAX_PENDING) {
                    var iter=pending.values().iterator();
                    var oldest=iter.next();
                    iter.remove();
                    oldest.partial();
                }
            }
            return work;
        }

        synchronized void finishChunk(Work work,boolean failed) {
            pending.remove(work.chunkKey,work);
            if(failed) work.failed();
            else work.complete();
        }

        synchronized boolean registerTile(Work work) { return !closed && tiles.add(work); }

        synchronized void record(Work work,String group,String location,long[] nanos,BitSet seen,long active) {
            if(closed) return;
            tiles.remove(work);
            total.computeIfAbsent(group,key -> new Stats(true)).record(location,nanos,seen,active);
            window.computeIfAbsent(group,key -> new Stats(false)).record(location,nanos,seen,active);
        }

        void flush(long now) {
            Map<String,Stats> snapshot;
            synchronized(this) {
                if(now<nextFlush || closed) return;
                nextFlush=now+WINDOW;
                var iterator=pending.values().iterator();
                while(iterator.hasNext()) {
                    var work=iterator.next();
                    if(now-work.lastStage>PARTIAL_TTL && !work.inStage) {
                        iterator.remove();
                        work.partial();
                    }
                }
                snapshot=new TreeMap<>(window);
                window.clear();
            }
            snapshot.forEach((group,stats) -> FarAwayClient.LOGGER.info("FarAway worldgen {} n={} {}",group,stats.count,stats.compact()));
        }

        synchronized String finish() {
            pending.values().forEach(Work::partial);
            pending.clear();
            List.copyOf(tiles).forEach(Work::partial);
            tiles.clear();
            running.clear();
            closed=true;
            var out=new StringBuilder("group\tphase\tused\tjobs\tavg_ms\tmedian_ms_approx\tp95_ms_approx\tactive_share_pct\n");
            total.forEach((group,stats) -> stats.append(out,group));
            out.append("\n# p95 followed by * has fewer than 20 samples. Active durations exclude wait and I/O rows.\n");
            out.append("\nslowest_group\tposition\tphase\tmilliseconds\n");
            total.forEach((group,stats) -> stats.slowest.stream().sorted(Comparator.comparingLong((Slow slow) -> slow.values()[0]).reversed())
                .forEach(slow -> {
                    for(Phase phase:Phase.values()) if(slow.seen().get(phase.ordinal()))
                        out.append(group).append('\t').append(slow.where()).append('\t').append(phase).append('\t')
                            .append(ms(slow.values()[phase.ordinal()])).append('\n');
                }));
            cacheProfile.append(out);
            return out.toString();
        }
    }

    private static final class Stats {
        final Histogram[] phases=new Histogram[PHASES];
        final PriorityQueue<Slow> slowest;
        long count,active;

        Stats(boolean keepSlowest) {
            slowest=keepSlowest?new PriorityQueue<>(Comparator.comparingLong(slow -> slow.values()[0])):null;
        }

        void record(String where,long[] values,BitSet seen,long active) {
            count++;this.active+=active;
            for(int i=seen.nextSetBit(0);i>=0;i=seen.nextSetBit(i+1)) {
                if(phases[i]==null) phases[i]=new Histogram();
                phases[i].add(values[i]);
            }
            if(slowest!=null && (slowest.size()<SLOWEST || values[0]>slowest.peek().values()[0])) {
                if(slowest.size()==SLOWEST) slowest.poll();
                slowest.add(new Slow(where,values,seen));
            }
        }

        String compact() {
            var out=new StringBuilder("ms avg/med/p95 ");
            for(Phase phase:Phase.values()) {
                var h=phases[phase.ordinal()];
                if(h==null) continue;
                out.append(phase).append('=').append(ms(h.sum/h.count)).append('/').append(ms(h.median()))
                    .append('/').append(ms(h.percentile(95))).append('(').append(h.count).append(") ");
            }
            return out.toString();
        }

        void append(StringBuilder out,String group) {
            for(Phase phase:Phase.values()) {
                var h=phases[phase.ordinal()];
                out.append(group).append('\t').append(phase).append('\t').append(h==null?0:h.count).append('\t').append(count).append('\t');
                if(h==null) out.append("-\t-\t-\t-");
                else {
                    out.append(ms(h.sum/h.count)).append('\t').append(ms(h.median())).append('\t')
                        .append(ms(h.percentile(95))).append(h.count<20?"*":"").append('\t')
                        .append(phase.latency() || phase==Phase.CACHE_HIT || active==0?"-":String.format(Locale.ROOT,"%.1f",100.0*h.sum/active));
                }
                out.append('\n');
            }
        }
    }

    // 1 µs buckets through 31 µs, then 16 subdivisions per power of two.
    static final class Histogram {
        final int[] buckets=new int[16*54];
        long count,sum;
        void add(long ns) {
            long us=ns/1000;
            int power=Math.max(0,63-Long.numberOfLeadingZeros(us)-4);
            int index=power*16+(int)(us>>power);
            buckets[Math.min(index,buckets.length-1)]++;
            count++;sum+=ns;
        }
        long percentile(int percent) {
            return atRank((count*percent+99)/100);
        }
        long median() {
            long lower=atRank((count+1)/2),upper=atRank(count/2+1);
            return lower+(upper-lower)/2;
        }
        private long atRank(long rank) {
            for(int i=0;i<buckets.length;i++) if((rank-=buckets[i])<=0) {
                int power=Math.max(0,i/16-1);
                return power==0?i*1000L:(16L+i%16)*(1000L<<power)+(500L<<power);
            }
            return 0;
        }
    }

    static String ms(long nanos) { return String.format(Locale.ROOT,"%.3f",nanos/1e6); }
}
