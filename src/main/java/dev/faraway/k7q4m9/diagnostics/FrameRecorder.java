package dev.faraway.k7q4m9.diagnostics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Single render-thread writer. Export a stopped snapshot off the render thread. */
public final class FrameRecorder {
    public static final long LIMIT_NS = 10_000_000L;
    private final long[] intervals;
    private int size;
    private long previous;
    private long dropped;
    private boolean active;

    public FrameRecorder(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        intervals = new long[capacity];
    }

    public void start(long now) {
        size = 0;
        dropped = 0;
        previous = now;
        active = true;
    }

    public void frame(long now) {
        if (!active) return;
        long elapsed = now - previous;
        if (elapsed < 0) throw new IllegalArgumentException("non-monotonic frame clock");
        previous = now;
        if (size < intervals.length) intervals[size++] = elapsed;
        else dropped++;
    }

    public Capture stop() {
        active = false;
        return new Capture(Arrays.copyOf(intervals, size), dropped);
    }

    public boolean active() { return active; }

    public static final class Capture {
        private final long[] frames;
        private final long dropped;

        private Capture(long[] frames, long dropped) {
            this.frames = frames;
            this.dropped = dropped;
        }

        public Summary summary() {
            long[] sorted = frames.clone();
            Arrays.sort(sorted);
            long violations = Arrays.stream(sorted).filter(n -> n > LIMIT_NS).count();
            return new Summary(sorted.length, dropped, violations,
                sorted.length == 0 ? 0 : sorted[sorted.length - 1],
                percentile(sorted, .50), percentile(sorted, .95), percentile(sorted, .99));
        }

        private static long percentile(long[] sorted, double quantile) {
            return sorted.length == 0 ? 0 : sorted[(int)Math.ceil(sorted.length * quantile) - 1];
        }

        public void writeCsv(Path path) throws IOException {
            Path parent = path.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            try (BufferedWriter out = Files.newBufferedWriter(path)) {
                out.write("frame,interval_ns\n");
                for (int i = 0; i < frames.length; i++) {
                    out.write(Integer.toString(i));
                    out.write(',');
                    out.write(Long.toString(frames[i]));
                    out.newLine();
                }
            }
        }
    }

    public record Summary(int frames, long dropped, long over10ms, long maxNs,
                          long p50Ns, long p95Ns, long p99Ns) {}
}
