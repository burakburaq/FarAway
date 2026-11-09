package dev.faraway.k7q4m9.worldgen;

import java.util.LinkedHashMap;
import java.util.concurrent.*;
import java.util.function.Supplier;
import dev.faraway.k7q4m9.terrain.TileKey;
import dev.faraway.k7q4m9.diagnostics.WorldgenCacheProfile.Future;
import dev.faraway.k7q4m9.diagnostics.WorldgenCacheProfile.Kind;
import dev.faraway.k7q4m9.client.LodSettings.ScratchTerrain;

/** Session-local single-flight cache of immutable placed blocks. */
public final class DecorationCache {
    private static final class Item {
        private final Future<DetachedDecorationSampler.Result> future; private final long bytes; private long used;
        Item(Future<DetachedDecorationSampler.Result> future,long bytes,long used) { this.future=future; this.bytes=bytes; this.used=used; }
        Future<DetachedDecorationSampler.Result> future() { return future; }
        long bytes() { return bytes; }
        long used() { return used; }
    }
    private record CacheKey(TileKey tile,boolean structures,boolean trees,ScratchTerrain scratch) {
        @Override public String toString() {
            return "block "+tile.originX()+","+tile.originZ()+" ("+tile.blocks()+" blocks, structures="+structures+", scratch="+scratch.label()+")";
        }
    }
    public enum Source { GENERATED, HIT, WAITED }
    public record TimedResult(DetachedDecorationSampler.Result result,Source source,long nanos) {}
    private final LinkedHashMap<CacheKey,Item> entries=new LinkedHashMap<>(64,.75f,true);
    private long bytes;
    public TimedResult get(TileKey tile,boolean trees,long limit,Supplier<DetachedDecorationSampler.Result> generate) {return get(tile,true,trees,limit,generate);}
    public TimedResult get(TileKey tile,boolean structures,boolean trees,long limit,Supplier<DetachedDecorationSampler.Result> generate) {
        return get(tile,structures,trees,ScratchTerrain.OFF,limit,generate);
    }
    public TimedResult get(TileKey tile,boolean structures,boolean trees,ScratchTerrain scratch,long limit,Supplier<DetachedDecorationSampler.Result> generate) {
        long start=System.nanoTime();
        var key=new CacheKey(tile,structures,trees,scratch);
        Item item; boolean owner=false;
        synchronized(this) {
            item=entries.get(key);
            if(item==null) { item=new Item(new Future<>(),0,System.nanoTime()); entries.put(key,item); owner=true; }
            else item.used=System.nanoTime();
        }
        Source source=owner?Source.GENERATED:item.future().isDone()?Source.HIT:Source.WAITED;
        Kind kind=trees?Kind.TREE_REGION:Kind.STRUCTURE_PATCH;
        if(owner) {
            try {
                DetachedDecorationSampler.Result result;
                try(var guard=CachePause.producing();var profile=item.future().build(kind)) {
                    result=generate.get();
                    long size=256L+result.blocks().size()*160L;
                    synchronized(this) {
                        if(entries.get(key)==item) {
                            if(size>limit) entries.remove(key);
                            else { entries.put(key,new Item(item.future(),size,System.nanoTime())); bytes+=size; trim(limit,key); }
                        }
                    }
                }
                item.future().complete(result);
            } catch(Throwable failure) {
                synchronized(this) { if(entries.get(key)==item) entries.remove(key); }
                item.future().completeExceptionally(failure);
            }
        }
        try {
            var result=owner?item.future().join():item.future().read(kind,key);
            return new TimedResult(result,source,System.nanoTime()-start);
        }
        catch(CompletionException e) {
            if(e.getCause() instanceof CancellationException c) throw c;
            if(e.getCause() instanceof RuntimeException r) throw r;
            throw e;
        }
    }
    private void trim(long limit,CacheKey protectedKey) {
        var it=entries.entrySet().iterator();
        while(bytes>limit && it.hasNext()) {
            var entry=it.next(); if(entry.getKey().equals(protectedKey) || !entry.getValue().future().isDone()) continue;
            bytes-=entry.getValue().bytes(); it.remove();
        }
    }
    public synchronized void clear() { entries.clear(); bytes=0; }
    public synchronized void expire(long now) {
        var it=entries.entrySet().iterator();
        while(it.hasNext()) {
            var entry=it.next();
            var item=entry.getValue();
            // One tree region feeds several tiles and can itself take over a second.
            long ttl=entry.getKey().trees()?15_000_000_000L:1_000_000_000L;
            if(item.future().isDone() && now-item.used()>=ttl) { bytes-=item.bytes(); it.remove(); }
        }
    }
}
