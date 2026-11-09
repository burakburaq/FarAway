package dev.faraway.k7q4m9.diagnostics;

import java.util.ArrayList;
import java.util.List;

/** Three runs per candidate, doubling then narrowing to a 32-chunk bracket.
 * A search covers one fixed scene/profile; cross-scene acceptance remains separate. */
public final class DistanceSearch {
    public interface Host {
        void runCandidate(int chunks);
        void finished(Report report);
    }
    public record Attempt(int chunks,int repeat,DistanceBenchmark.Result result) {}
    public record Report(int highestStreamingPassedChunks,int highestResidentPassedChunks,Integer failedUpperChunks,boolean capped,
                         String reason,List<Attempt> attempts) {}
    private final Host host;
    private final int ceiling;
    private final List<Attempt> attempts=new ArrayList<>();
    private int candidate=64,repeat,lower,upper;
    private boolean candidatePassed=true,active;

    public DistanceSearch(Host host,int ceiling) {
        if(ceiling<64 || ceiling%32!=0) throw new IllegalArgumentException("Search ceiling must be a multiple of 32 and at least 64");
        this.host=host; this.ceiling=ceiling;
    }
    public boolean active() { return active; }
    public void start() {
        if(active || !attempts.isEmpty()) throw new IllegalStateException("Create a new search for each profile/scene");
        active=true; host.runCandidate(candidate);
    }
    public void accept(DistanceBenchmark.Result result) {
        if(!active) return;
        if(result.radiusChunks()!=candidate) throw new IllegalArgumentException("Result belongs to another candidate");
        attempts.add(new Attempt(candidate,++repeat,result));
        if(result.cancelled() || DistanceBenchmark.baselinePhase(result.failedPhase())) {
            finish(false,"Search interrupted or baseline failed; no distance bound inferred"); return;
        }
        candidatePassed &= result.passed();
        if(repeat<3) { host.runCandidate(candidate); return; }
        if(candidatePassed) lower=candidate; else upper=candidate;
        if(lower==0) {
            if(candidate>32) { candidate=32; repeat=0; candidatePassed=true; host.runCandidate(candidate); return; }
            finish(false,"Both initial and 32-chunk fallback failed; no successful radius established"); return;
        }
        if(upper>0 && upper-lower<=32) { finish(false,"Three-run scene bracket established; other scenes still required"); return; }
        if(upper==0 && lower==ceiling) { finish(true,"Configured ceiling reached; highest tested radius, not a proven maximum"); return; }
        candidate=upper==0?Math.min(ceiling,Math.multiplyExact(lower,2)):(lower+(upper-lower)/2)/32*32;
        repeat=0; candidatePassed=true; host.runCandidate(candidate);
    }
    public void cancel(String reason) { if(active) finish(false,"Cancelled: "+reason); }
    private void finish(boolean capped,String reason) {
        active=false;
        int resident=attempts.stream().mapToInt(Attempt::chunks).distinct().filter(chunks -> {
            var runs=attempts.stream().filter(a -> a.chunks()==chunks).toList();
            return runs.size()==3 && runs.stream().allMatch(a -> a.result().residentPassed());
        }).max().orElse(0);
        host.finished(new Report(lower,resident,upper==0?null:upper,capped,reason,List.copyOf(attempts)));
    }
}
