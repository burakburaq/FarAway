package dev.faraway.k7q4m9.diagnostics;

import java.lang.management.ManagementFactory;
import javax.management.NotificationEmitter;
import com.sun.management.GarbageCollectionNotificationInfo;

/** Asynchronous collector notifications, bounded event history, no JFR recording required. */
final class GcEventProfile {
    private final java.util.ArrayList<Event> events=new java.util.ArrayList<>(16);
    private boolean active,registered;
    private long count,totalMs,maxMs,dropped;
    synchronized void start() {
        active=true;
        if(registered) return;
        registered=true;
        for(var bean:ManagementFactory.getGarbageCollectorMXBeans()) if(bean instanceof NotificationEmitter emitter) {
            try {
                emitter.addNotificationListener((notification,handback) -> {
                    if(!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) return;
                    var info=GarbageCollectionNotificationInfo.from((javax.management.openmbean.CompositeData)notification.getUserData());
                    var gc=info.getGcInfo();
                    synchronized(this) {
                        if(!active) return;
                        count++; totalMs+=gc.getDuration(); maxMs=Math.max(maxMs,gc.getDuration());
                        var event=new Event(info.getGcName(),info.getGcAction(),info.getGcCause(),gc.getStartTime(),gc.getDuration());
                        if(events.size()<16) events.add(event);
                        else { dropped++; int smallest=0; for(int i=1;i<events.size();i++) if(events.get(i).durationMs()<events.get(smallest).durationMs()) smallest=i; if(event.durationMs()>events.get(smallest).durationMs()) events.set(smallest,event); }
                    }
                },null,null);
            } catch(RuntimeException failure) {
                dev.faraway.k7q4m9.client.FarAwayClient.LOGGER.warn("FarAway GC event notifications unavailable for {}",bean.getName(),failure);
            }
        }
    }
    synchronized Snapshot drain() {
        var snapshot=new Snapshot(count,totalMs,maxMs,dropped,java.util.List.copyOf(events));
        count=totalMs=maxMs=dropped=0; events.clear(); return snapshot;
    }
    synchronized void stop() { active=false; drain(); }
    record Event(String collector,String action,String cause,long startUptimeMs,long durationMs) {}
    record Snapshot(long count,long totalMs,long maxMs,long omitted,java.util.List<Event> events) {}
}

