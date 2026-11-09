package dev.faraway.k7q4m9.streaming;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Reservations travel with queued results; closing twice cannot release twice. */
public final class ByteBudget {
    private volatile long limit;
    private final AtomicLong used = new AtomicLong();
    private final AtomicLong peak = new AtomicLong();
    public ByteBudget(long limit) {
        if (limit < 1) throw new IllegalArgumentException("Budget must be positive");
        this.limit = limit;
    }
    public Reservation tryReserve(long bytes) {
        if (bytes < 1 || bytes > limit) return null;
        for (;;) {
            long current = used.get();
            if (bytes > limit-current) return null;
            if (used.compareAndSet(current,current+bytes)) {
                peak.accumulateAndGet(current+bytes,Math::max);
                return new Reservation(bytes);
            }
        }
    }
    public long used() { return used.get(); }
    public long limit() { return limit; }
    public void setLimit(long limit) {
        if (limit < 1) throw new IllegalArgumentException("Budget must be positive");
        this.limit=limit;
    }
    public long peak() { return peak.get(); }
    public void resetPeak() { peak.set(used.get()); }
    public final class Reservation implements AutoCloseable {
        private volatile long bytes;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Reservation(long bytes) { this.bytes=bytes; }
        public long bytes() { return bytes; }
        public synchronized boolean tryGrow(long targetBytes) {
            if(closed.get()) return false;
            if(targetBytes<=bytes) return true;
            long extra=targetBytes-bytes;
            for(;;) {
                long current=used.get();
                if(extra>limit-current) return false;
                if(used.compareAndSet(current,current+extra)) {
                    bytes=targetBytes; peak.accumulateAndGet(current+extra,Math::max); return true;
                }
            }
        }
        @Override public synchronized void close() { if (closed.compareAndSet(false,true)) used.addAndGet(-bytes); }
    }
}
