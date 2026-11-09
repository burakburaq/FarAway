package dev.faraway.k7q4m9.diagnostics;

import dev.faraway.k7q4m9.render.PreviewRenderer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Bounded raw coverage trace; a final resident count alone cannot prove movement coverage. */
public final class CoverageRecorder {
    public record Observation(long elapsedNs,double x,double y,double z,long heapUsed,
                              PreviewRenderer.Metrics metrics) {}
    public record Capture(List<Observation> observations,long dropped) {
        public void writeCsv(Path file) throws IOException {
            try(var out=Files.newBufferedWriter(file)) {
                out.write("elapsed_ns,x,y,z,heap_used,radius_chunks,wanted,resident,drawn,gpu_bytes,pending_bytes,oldest_missing_ns,failed,resource_limit,upload_ns,gpu_peak_bytes,pending_peak_bytes,prefetch_ready,vanilla_ready_columns\n");
                for(var row:observations) {
                    var m=row.metrics();
                    out.write(row.elapsedNs()+","+row.x()+","+row.y()+","+row.z()+","+row.heapUsed()+","+m.radiusChunks()+","+m.wanted()+","+m.resident()+","+m.drawn()+","+m.gpuBytes()+","+m.pendingBytes()+","+m.oldestMissingNanos()+","+m.failed()+","+m.resourceLimit()+","+m.uploadNanos()+","+m.gpuPeakBytes()+","+m.pendingPeakBytes()+","+m.prefetchReady()+","+m.vanillaReadyColumns()+"\n");
                }
            }
        }
    }
    private final ArrayList<Observation> observations=new ArrayList<>();
    private long start,next,dropped;
    private boolean active;
    public void start(long now) { observations.clear(); start=next=now; dropped=0; active=true; }
    public void observe(long now,double x,double y,double z,PreviewRenderer.Metrics metrics) {
        if(!active || now<next) return;
        next=now+100_000_000L;
        if(observations.size()==36_000) { dropped++; return; }
        var runtime=Runtime.getRuntime();
        observations.add(new Observation(now-start,x,y,z,runtime.totalMemory()-runtime.freeMemory(),metrics));
    }
    public Capture stop() { active=false; return new Capture(List.copyOf(observations),dropped); }
}
