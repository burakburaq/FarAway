package dev.faraway.k7q4m9.streaming;

import dev.faraway.k7q4m9.terrain.TileKey;
import java.util.Set;
import java.util.concurrent.*;

/** One immutable accepted plan, at most one background calculation. Latest request replaces pending input. */
public final class LodPlanningSession implements AutoCloseable {
    public record Request(Set<TileKey> roots,int x,int y,int z,int minY,int maxY,int height,double fov,double pixels,int budget,double radius,
                          LodScatter.Config scatter,ScreenSpaceLod projection,int lower,int upper) {
        public Request { roots=Set.copyOf(roots); }
        boolean compatible(Request b) { return b!=null && roots.equals(b.roots) && minY==b.minY && maxY==b.maxY && height==b.height && fov==b.fov && pixels==b.pixels && budget==b.budget && radius==b.radius && scatter.equals(b.scatter) && lower==b.lower && upper==b.upper && (projection==null)==(b.projection==null); }
        boolean same(Request b) { return compatible(b) && x==b.x && y==b.y && z==b.z && (projection==null || projection.sameView(b.projection)); }
    }
    private ExecutorService executor;
    private Future<DirectLodPlanner.Plan> future;
    private Request running,acceptedInput;
    private DirectLodPlanner.Plan accepted;
    private long nextStart;
    private boolean closed;
    public DirectLodPlanner.Plan accepted() { return accepted; }
    private DirectLodPlanner.Plan calculate(Request r,Set<TileKey> previous) {
        var planner=new DirectLodPlanner(r.scatter);planner.setProjection(r.projection);planner.setHistory(previous,r.lower,r.upper);
        return planner.plan(r.roots,r.x,r.y,r.z,r.minY,r.maxY,r.height,r.fov,r.pixels,r.budget,r.radius);
    }
    public DirectLodPlanner.Plan update(Request latest,boolean async,double hz,boolean locked) {
        if(closed)return null;
        if(locked && accepted!=null)return accepted;
        long now=System.nanoTime();
        if(future!=null && (!async || !latest.compatible(running))) { future.cancel(true);future=null;if(executor instanceof ThreadPoolExecutor pool)pool.getQueue().clear(); }
        if(future!=null && future.isDone()) {
            try { accepted=future.get();acceptedInput=running; }
            catch(CancellationException ignored) { }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            catch(ExecutionException e) { future=null;throw new IllegalArgumentException("LOD planning failed",e.getCause()); }
            future=null;
        }
        boolean hardChange=!latest.compatible(acceptedInput);
        if(future==null && !latest.same(acceptedInput) && (hardChange || now>=nextStart)) {
            Set<TileKey> previous=accepted==null?Set.of():accepted.leaves();
            nextStart=now+(long)(1_000_000_000.0/hz);
            if(async) {
                if(executor==null) executor=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),task -> {var thread=new Thread(task,"FarAway LOD planner");thread.setDaemon(true);return thread;});
                running=latest;future=executor.submit(()->calculate(latest,previous));
            } else { accepted=calculate(latest,previous);acceptedInput=latest; }
        }
        return accepted;
    }
    @Override public void close() { closed=true;if(future!=null)future.cancel(true);if(executor!=null)executor.shutdownNow();accepted=null; }
}
