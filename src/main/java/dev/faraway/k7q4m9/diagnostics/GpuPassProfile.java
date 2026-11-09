package dev.faraway.k7q4m9.diagnostics;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.faraway.k7q4m9.client.FarAwayClient;

/** Timestamp one frame in eight; poll old queries without blocking or reusing pending slots. */
public final class GpuPassProfile {
    private GpuQueryPool pool;
    private final boolean[] pending=new boolean[8];
    private final long[] issued=new long[8],values=new long[8];
    private long frame;
    private int active=-1;
    private final boolean[] written=new boolean[8];
    private boolean unavailable;
    public void begin(RenderPass pass) {
        active=-1; frame++;
        if(unavailable) return;
        try {
            if(pool==null) {
                pool=RenderSystem.getDevice().createTimestampQueryPool(64);
                FarAwayClient.LOGGER.info("FarAway GPU profiling enabled: timestamp sampling=1/8, slots=8, readback=nonblocking");
            }
            for(int slot=0;slot<8;slot++) if(pending[slot] && frame-issued[slot]>=4) {
                // A ready last timestamp does not imply all results are visible on every backend.
                var last=pool.getValue(slot*8+7); if(last.isEmpty()) continue;
                values[7]=last.getAsLong(); boolean ready=true;
                for(int i=0;i<7;i++) {
                    var value=pool.getValue(slot*8+i);
                    if(value.isEmpty()) { ready=false; break; }
                    values[i]=value.getAsLong();
                }
                if(!ready) continue;
                for(int i=0;i<4;i++) RenderProfile.INSTANCE.gpu(i,values[i*2+1]-values[i*2]);
                pending[slot]=false;
            }
            if((frame&7)!=0) return;
            for(int slot=0;slot<8;slot++) if(!pending[slot]) { active=slot; java.util.Arrays.fill(written,false); return; }
            RenderProfile.INSTANCE.skippedGpu();
        } catch(RuntimeException failure) { disable(failure); }
    }
    public void point(RenderPass pass,int index) {
        if(active<0) return;
        try { if(!written[index]) { pass.writeTimestamp(pool,active*8+index); written[index]=true; } }
        catch(RuntimeException failure) { disable(failure); }
    }
    public void startStage(RenderPass pass,int stage) { point(pass,stage*2); }
    public void endStage(RenderPass pass,int stage) { startStage(pass,stage); point(pass,stage*2+1); }
    public void end(RenderPass pass) {
        for(int i=0;i<8;i++) point(pass,i);
        if(active>=0) { pending[active]=true; issued[active]=frame; active=-1; }
    }
    private void disable(RuntimeException failure) {
        unavailable=true; active=-1;
        FarAwayClient.LOGGER.warn("FarAway GPU timestamps unavailable; CPU profiling remains active",failure);
        release();
    }
    private void release() { var old=pool; pool=null; if(old!=null) RenderSystem.queueFencedTask(old::close); }
    public void reset() { release(); java.util.Arrays.fill(pending,false); active=-1; frame=0; unavailable=false; }
}
