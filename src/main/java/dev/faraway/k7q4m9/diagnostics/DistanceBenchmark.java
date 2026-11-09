package dev.faraway.k7q4m9.diagnostics;

import dev.faraway.k7q4m9.render.PreviewRenderer;
import java.util.ArrayList;
import java.util.List;

/** Scenario driver. A candidate passes only after all gameplay phases and coverage checks. */
public final class DistanceBenchmark {
    public enum Phase { IDLE,PREPARING_BASELINE,BASELINE,BASELINE_MOVING,BASELINE_TELEPORT,PREPARING_COLD,COLD,RESIDENT,MOVING,TELEPORT,DONE,FAILED }
    public static boolean baselinePhase(Phase phase) {
        return phase==Phase.PREPARING_BASELINE || phase==Phase.BASELINE || phase==Phase.BASELINE_MOVING
            || phase==Phase.BASELINE_TELEPORT || phase==Phase.PREPARING_COLD;
    }
    public interface Host {
        void clearTerrain();
        void startCandidate(int radiusChunks);
        void startCapture(String label);
        GameFpsRecorder.Summary finishCapture(String label);
        PreviewRenderer.Metrics metrics();
        void moveAlongRoute(double elapsedSeconds);
        void teleportRoute();
        void result(Result result);
        default String invalidReason() { return null; }
        default boolean teleportArrived() { return true; }
        default void rotateView(double elapsedSeconds) {}
        default String captureId() { return ""; }
        default boolean vanillaReady() { return true; }
        default boolean originArrived() { return true; }
        default void resetRoute() {}
    }
    public record Result(int radiusChunks,boolean passed,String reason,double fillSeconds,
                         List<GameFpsRecorder.Summary> fps,Phase failedPhase,boolean cancelled,List<PhaseEvidence> phases) {
        public boolean residentPassed() {
            return phases.stream().anyMatch(p -> p.phase()==Phase.RESIDENT && p.durationNs()>=120_000_000_000L
                && p.start().fullCoverage() && p.end().fullCoverage() && p.fps().passes());
        }
    }
    public record PhaseEvidence(Phase phase,String captureId,long durationNs,PreviewRenderer.Metrics start,
                                PreviewRenderer.Metrics end,GameFpsRecorder.Summary fps) {}
    private final Host host;
    private final List<GameFpsRecorder.Summary> captures=new ArrayList<>();
    private final List<PhaseEvidence> evidence=new ArrayList<>();
    private PreviewRenderer.Metrics phaseMetrics;
    private String captureId;
    private long lastNow;
    private Phase phase=Phase.IDLE;
    private int radius;
    private long phaseStart,coverageReady;
    private double fillSeconds;
    private boolean baselinePass;
    private boolean capturing;

    public DistanceBenchmark(Host host) { this.host=host; }
    public Phase phase() { return phase; }
    public void start(int radiusChunks,long now) {
        if(phase!=Phase.IDLE && phase!=Phase.DONE && phase!=Phase.FAILED) throw new IllegalStateException("Benchmark already running");
        if(radiusChunks<1) throw new IllegalArgumentException("Invalid radius");
        radius=radiusChunks; captures.clear(); evidence.clear(); lastNow=now; coverageReady=0; fillSeconds=0; baselinePass=false;
        host.clearTerrain();
        prepare(Phase.PREPARING_BASELINE,now);
    }
    public void update(long now) {
        lastNow=now;
        if(phase!=Phase.IDLE && phase!=Phase.DONE && phase!=Phase.FAILED) {
            String invalid=host.invalidReason();
            if(invalid!=null) { cancel(invalid); return; }
        }
        double seconds=(now-phaseStart)/1e9;
        switch(phase) {
            case PREPARING_BASELINE,PREPARING_COLD -> {
                if(seconds>120) { fail("Vanilla/origin preparation timeout"); return; }
                if(!host.originArrived() || !host.vanillaReady()) return;
                if(phase==Phase.PREPARING_BASELINE) enter(Phase.BASELINE,now,"baseline-resident-r"+radius);
                else {
                    baselinePass=true; host.startCandidate(radius);
                    enter(Phase.COLD,now,"cold-r"+radius);
                }
            }
            case BASELINE -> {
                host.rotateView(Math.min(seconds,120));
                if(seconds<120) return;
                if(!finish("baseline-resident-r"+radius).passes()) { fail("Baseline resident FPS below target or missing samples"); return; }
                enter(Phase.BASELINE_MOVING,now,"baseline-moving-r"+radius);
            }
            case BASELINE_MOVING -> {
                host.moveAlongRoute(Math.min(seconds,60));
                if(seconds<60) return;
                if(!finish("baseline-moving-r"+radius).passes()) { fail("Baseline movement FPS below target or missing samples"); return; }
                host.teleportRoute(); enter(Phase.BASELINE_TELEPORT,now,"baseline-teleport-r"+radius);
            }
            case BASELINE_TELEPORT -> {
                if(seconds>120) { fail("Baseline teleport/vanilla preparation timeout"); return; }
                if(!host.teleportArrived() || !host.vanillaReady()) { coverageReady=0; return; }
                if(coverageReady==0) coverageReady=now;
                if(now-coverageReady<1_100_000_000L) return;
                if(!finish("baseline-teleport-r"+radius).passes()) { fail("Baseline teleport FPS below target or missing samples"); return; }
                host.resetRoute(); prepare(Phase.PREPARING_COLD,now);
            }
            case COLD,TELEPORT -> {
                var metrics=host.metrics();
                if(metrics.resourceLimit() || metrics.failed()>0) { fail("Resource limit or failed tile: "+metrics); return; }
                if(seconds>120) { fail("Full coverage fill timeout: "+metrics); return; }
                if(phase==Phase.TELEPORT && !host.teleportArrived()) { coverageReady=0; return; }
                if(!metrics.fullCoverage()) { coverageReady=0; return; }
                if(coverageReady==0) coverageReady=now;
                // Require a full HUD counter update after fill; an empty capture cannot pass.
                if(now-coverageReady<1_100_000_000L) return;
                var fps=finish(phase.name().toLowerCase()+"-r"+radius);
                if(!fps.passes()) { fail("Game FPS below target during "+phase); return; }
                if(phase==Phase.COLD) { fillSeconds=seconds; enter(Phase.RESIDENT,now,"resident-r"+radius); }
                else complete();
            }
            case RESIDENT -> {
                host.rotateView(Math.min(seconds,120));
                if(!host.metrics().fullCoverage()) { fail("Resident coverage incomplete"); return; }
                if(seconds<120) return;
                if(!host.metrics().prefetchReady()) { fail("Prefetch not ready before movement"); return; }
                if(!finish("resident-r"+radius).passes()) { fail("Resident game FPS below target"); return; }
                enter(Phase.MOVING,now,"moving-r"+radius);
            }
            case MOVING -> {
                host.moveAlongRoute(Math.min(seconds,60));
                if(!host.metrics().fullCoverage()) { fail("Full target coverage lost during movement"); return; }
                if(seconds<60) return;
                if(!finish("moving-r"+radius).passes()) { fail("Moving game FPS below target"); return; }
                host.teleportRoute();
                enter(Phase.TELEPORT,now,"teleport-r"+radius);
            }
            default -> { }
        }
    }
    private void prepare(Phase next,long now) { phase=next; phaseStart=now; coverageReady=0; capturing=false; }
    private void enter(Phase next,long now,String label) {
        phase=next; phaseStart=now; coverageReady=0; host.startCapture(label); capturing=true;
        captureId=host.captureId(); phaseMetrics=host.metrics();
    }
    private GameFpsRecorder.Summary finish(String label) {
        if(!capturing) throw new IllegalStateException("No active benchmark capture");
        capturing=false;
        var summary=host.finishCapture(label); captures.add(summary);
        evidence.add(new PhaseEvidence(phase,captureId,Math.max(0,lastNow-phaseStart),phaseMetrics,host.metrics(),summary));
        return summary;
    }
    private void complete() {
        phase=Phase.DONE;
        host.result(new Result(radius,baselinePass,"All candidate phases passed; repeat/scene validation still required",fillSeconds,List.copyOf(captures),null,false,List.copyOf(evidence)));
    }
    private void fail(String reason) { fail(reason,false); }
    private void fail(String reason,boolean cancelled) {
        Phase failedPhase=phase;
        if(capturing) finish("failed-"+failedPhase.name().toLowerCase()+"-r"+radius);
        phase=Phase.FAILED;
        host.result(new Result(radius,false,reason,fillSeconds,List.copyOf(captures),failedPhase,cancelled,List.copyOf(evidence)));
    }
    public void cancel(String reason) { if(phase!=Phase.IDLE && phase!=Phase.DONE && phase!=Phase.FAILED) fail("Cancelled: "+reason,true); }
}

