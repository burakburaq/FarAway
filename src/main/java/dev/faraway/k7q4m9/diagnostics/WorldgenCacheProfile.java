package dev.faraway.k7q4m9.diagnostics;

import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Phase;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler.Work;
import dev.faraway.k7q4m9.worldgen.CachePause;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Single-flight cache timings attached to the existing futures. */
public final class WorldgenCacheProfile {
    public enum Kind {
        DENSE_SURFACE, LAVA_LAKES, VEGETATION_BIOMES, DETACHED_TERRAIN,
        TREE_REGION, STRUCTURE_PATCH, STRUCTURE_CANDIDATE, STRUCTURE_INDEX, SELECTED_SURFACE
    }

    record Job(long id,String group,String location) {
        String label() { return group+" "+location+" #"+id; }
    }

    private static final int SLOWEST=20, MAX_CHAIN=4;
    private static final ThreadLocal<Flight> BUILDING=new ThreadLocal<>();
    private final long sessionId;
    private final AtomicLong nextJob=new AtomicLong();
    private final Counter[] counters=new Counter[Kind.values().length];
    private final PriorityQueue<Wait> slowest=new PriorityQueue<>(Comparator.comparingLong(Wait::nanos));
    private boolean closed;

    WorldgenCacheProfile(long sessionId) {
        this.sessionId=sessionId;
        for(int i=0;i<counters.length;i++) counters[i]=new Counter();
    }

    Job job(String group,String location) { return new Job(nextJob.incrementAndGet(),group,location); }

    /** Marks the thread that actually invokes the callable, including a racing caller of run(). */
    public static final class Task<V> extends FutureTask<V> {
        private final Entry entry;
        private final Kind kind;
        private volatile CompletableFuture<Void> signal;

        public Task(Kind kind,Callable<V> action) { this(new Entry(),kind,action); }

        private Task(Entry entry,Kind kind,Callable<V> action) {
            super(() -> {
                try(var guard=CachePause.producing();var build=entry.build(kind)) { return action.call(); }
            });
            this.entry=entry;
            this.kind=kind;
        }

        public V read(Object key) throws InterruptedException,ExecutionException {
            Work work=WorldgenProfiler.activeWork();
            boolean ready=work!=null && isDone();
            run();
            if(!isDone() && CachePause.allowed()) CachePause.waitFor(completionSignal());
            if(work==null) return get();
            var flight=entry.flight;
            if(!ready && flight!=null && flight.sessionId==work.cacheProfile().sessionId
                && flight.producer==work.cacheJob() && flight.threadId==Thread.currentThread().threadId()) return get();
            try(var lookup=entry.lookup(work,kind,key,isDone())) { return get(); }
        }

        private synchronized CompletableFuture<Void> completionSignal() {
            if(signal==null) signal=new CompletableFuture<>();
            if(isDone()) signal.complete(null);
            return signal;
        }

        @Override protected void done() {
            var completion=signal;
            if(completion!=null) completion.complete(null);
        }
    }

    public static final class Future<V> extends CompletableFuture<V> {
        private final Entry entry=new Entry();
        public Build build(Kind kind) { return entry.build(kind); }
        public V read(Kind kind,Object key) {
            CachePause.waitFor(this);
            try(var lookup=entry.lookup(WorldgenProfiler.activeWork(),kind,key,isDone())) { return join(); }
        }
    }

    private static final class Entry {
        private volatile Flight flight;

        private Flight flight(long sessionId) {
            var existing=flight;
            if(existing!=null && existing.sessionId==sessionId) return existing;
            synchronized(this) {
                if(flight==null || flight.sessionId!=sessionId) flight=new Flight(sessionId);
                return flight;
            }
        }

        Build build(Kind kind) {
            var work=WorldgenProfiler.activeWork();
            if(work==null) return Build.NONE;
            var profile=work.cacheProfile();
            var result=flight(profile.sessionId);
            result.producer=work.cacheJob();
            result.threadId=Thread.currentThread().threadId();
            return new Build(profile,kind,result);
        }

        Lookup lookup(Work work,Kind kind,Object key,boolean ready) {
            if(work==null) return Lookup.NONE;
            var profile=work.cacheProfile();
            if(ready) profile.counters[kind.ordinal()].hits.increment();
            return new Lookup(work,kind,key,ready?null:flight(profile.sessionId));
        }
    }

    public static final class Build implements AutoCloseable {
        private static final Build NONE=new Build();
        private final WorldgenCacheProfile profile;
        private final Kind kind;
        private final Flight flight,parent;
        private final long started;

        private Build() { profile=null;kind=null;flight=parent=null;started=0; }

        private Build(WorldgenCacheProfile profile,Kind kind,Flight flight) {
            this.profile=profile;
            this.kind=kind;
            this.flight=flight;
            parent=BUILDING.get();
            BUILDING.set(flight);
            started=System.nanoTime();
        }

        @Override public void close() {
            if(profile==null) return;
            long elapsed=System.nanoTime()-started;
            flight.buildNanos=elapsed;
            if(parent==null) BUILDING.remove();
            else BUILDING.set(parent);
            profile.recordBuild(kind,elapsed,flight,parent);
        }
    }

    private static final class Lookup implements AutoCloseable {
        static final Lookup NONE=new Lookup();
        final Work work;
        final Kind kind;
        final Object key;
        final Flight flight;
        final long started;
        final WorldgenProfiler.Scope scope;

        private Lookup() { work=null;kind=null;key=null;flight=null;started=0;scope=null; }

        Lookup(Work work,Kind kind,Object key,Flight flight) {
            this.work=work;
            this.kind=kind;
            this.key=key;
            this.flight=flight;
            scope=WorldgenProfiler.scope(work,flight==null?Phase.CACHE_HIT:Phase.CACHE_WAIT);
            started=flight==null?0:System.nanoTime();
        }

        @Override public void close() {
            if(work==null) return;
            long elapsed=flight==null?0:System.nanoTime()-started;
            scope.close();
            if(flight!=null) work.cacheProfile().recordWait(kind,key,flight,work,elapsed);
        }
    }

    private static final class Flight {
        final long sessionId;
        volatile Job producer;
        volatile long threadId,buildNanos=-1;
        // One profile monitor protects these counters and retained dependency summaries.
        HashSet<Job> waiters;
        long longestDependency;
        List<String> chain=List.of();

        Flight(long sessionId) { this.sessionId=sessionId; }
    }

    private record Wait(Kind kind,String key,Flight flight,Job waiter,long nanos) {}

    private static final class Counter {
        final LongAdder hits=new LongAdder();
        final WorldgenProfiler.Histogram builds=new WorldgenProfiler.Histogram(), waits=new WorldgenProfiler.Histogram();
        long waitingJobs,waitedResults,maxWait;
    }

    private synchronized void recordBuild(Kind kind,long nanos,Flight flight,Flight parent) {
        if(closed) return;
        counters[kind.ordinal()].builds.add(nanos);
        if(parent!=null && parent.sessionId==sessionId && flight.longestDependency>parent.longestDependency) {
            parent.longestDependency=flight.longestDependency;
            parent.chain=flight.chain;
        }
    }

    private synchronized void recordWait(Kind kind,Object key,Flight flight,Work work,long nanos) {
        if(closed) return;
        var stats=counters[kind.ordinal()];
        stats.waits.add(nanos);
        stats.maxWait=Math.max(stats.maxWait,nanos);
        if(work.markCacheWait(kind)) stats.waitingJobs++;
        if(flight.waiters==null) {
            stats.waitedResults++;
            flight.waiters=new HashSet<>();
        }
        flight.waiters.add(work.cacheJob());
        var parent=BUILDING.get();
        if(parent!=null && parent.sessionId==sessionId && nanos>parent.longestDependency) {
            var chain=new ArrayList<String>(MAX_CHAIN);
            var producer=flight.producer;
            chain.add(kind+" "+key+" "+WorldgenProfiler.ms(nanos)+"ms -> "+(producer==null?"?":producer.label()));
            chain.addAll(flight.chain.subList(0,Math.min(flight.chain.size(),MAX_CHAIN-1)));
            parent.longestDependency=nanos;
            parent.chain=List.copyOf(chain);
        }
        if(slowest.size()<SLOWEST || nanos>slowest.peek().nanos()) {
            if(slowest.size()==SLOWEST) slowest.poll();
            slowest.add(new Wait(kind,key.toString(),flight,work.cacheJob(),nanos));
        }
    }

    synchronized void append(StringBuilder out) {
        closed=true;
        out.append("\n# Cache waits sum blocked-worker time. Waited results count shared builds once per cache.\n");
        out.append("cache\thits\tbuilds\tbuild_avg_ms\tbuild_p95_ms_approx\twait_calls\twaiting_jobs\twaited_results\twait_total_ms\twait_median_ms_approx\twait_p95_ms_approx\twait_max_ms\n");
        for(var kind:Kind.values()) {
            var stats=counters[kind.ordinal()];
            long hits=stats.hits.sum(),builds=stats.builds.count,waits=stats.waits.count;
            if(hits+builds+waits==0) continue;
            out.append(kind).append('\t').append(hits).append('\t').append(builds).append('\t');
            if(builds==0) out.append("-\t-");
            else out.append(WorldgenProfiler.ms(stats.builds.sum/builds)).append('\t').append(p95(stats.builds));
            out.append('\t').append(waits).append('\t').append(stats.waitingJobs).append('\t').append(stats.waitedResults).append('\t')
                .append(WorldgenProfiler.ms(stats.waits.sum)).append('\t');
            if(waits==0) out.append("-\t-\t-");
            else out.append(WorldgenProfiler.ms(stats.waits.median())).append('\t').append(p95(stats.waits)).append('\t').append(WorldgenProfiler.ms(stats.maxWait));
            out.append('\n');
        }
        out.append("\n# Slowest completed wait calls. Producer is unknown for builds started outside this profiling session.\n");
        out.append("waiting_job\tcache_kind\tcache_key\twait_ms\tproducer_job\tproducer_build_ms\tjobs_waiting_on_result\tproducer_longest_dependency_chain\n");
        slowest.stream().sorted(Comparator.comparingLong(Wait::nanos).reversed()).forEach(wait -> {
            var result=wait.flight();
            var producer=result.producer;
            out.append(wait.waiter().label()).append('\t').append(wait.kind()).append('\t').append(wait.key()).append('\t')
                .append(WorldgenProfiler.ms(wait.nanos())).append('\t').append(producer==null?"-":producer.label()).append('\t')
                .append(result.buildNanos<0?"-":WorldgenProfiler.ms(result.buildNanos)).append('\t').append(result.waiters.size()).append('\t')
                .append(result.chain.isEmpty()?"-":String.join(" | ",result.chain)).append('\n');
        });
    }

    private static String p95(WorldgenProfiler.Histogram histogram) {
        return WorldgenProfiler.ms(histogram.percentile(95))+(histogram.count<20?"*":"");
    }
}
