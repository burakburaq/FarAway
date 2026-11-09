package dev.faraway.k7q4m9.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Records the game's displayed FPS counter, not inverse individual frame time. */
public final class GameFpsRecorder {
    public static final long PREPARATION_NS = 1_000_000_000L;
    private static final long SAMPLE_INTERVAL_NS = 100_000_000L;
    private final long[] times;
    private final int[] values;
    private long start, nextSample, dropped;
    private int size;
    private boolean active;

    public GameFpsRecorder(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        times = new long[capacity];
        values = new int[capacity];
    }

    public void start(long now) {
        start = now;
        nextSample = now + PREPARATION_NS;
        size = 0;
        dropped = 0;
        active = true;
    }

    public void observe(long now, int gameFps) {
        if (!active || now < nextSample) return;
        nextSample = now + SAMPLE_INTERVAL_NS;
        if (size == values.length) { dropped++; return; }
        times[size] = now - start;
        values[size++] = gameFps;
    }

    public Capture stop() {
        active = false;
        return new Capture(Arrays.copyOf(times, size), Arrays.copyOf(values, size), dropped);
    }

    public static final class Capture {
        private final long[] times;
        private final int[] values;
        private final long dropped;
        private Capture(long[] times, int[] values, long dropped) {
            this.times = times; this.values = values; this.dropped = dropped;
        }
        public Summary summary() {
            var stats = Arrays.stream(values).summaryStatistics();
            return new Summary(values.length, dropped, values.length == 0 ? 0 : stats.getMin(),
                values.length == 0 ? 0 : stats.getMax(), Arrays.stream(values).filter(v -> v < 100).count());
        }
        public void writeCsv(Path file) throws IOException {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (var out = Files.newBufferedWriter(file)) {
                out.write("elapsed_ns,minecraft_fps\n");
                for (int i = 0; i < values.length; i++) {
                    out.write(times[i] + "," + values[i]);
                    out.newLine();
                }
            }
        }
    }

    public record Summary(int observations, long dropped, int minFps, int maxFps, long below100) {
        public boolean passes() { return observations > 0 && dropped == 0 && below100 == 0; }
    }
}
