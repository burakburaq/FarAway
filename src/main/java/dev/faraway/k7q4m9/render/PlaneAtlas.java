package dev.faraway.k7q4m9.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.*;
import dev.faraway.k7q4m9.streaming.ByteBudget;
import dev.faraway.k7q4m9.terrain.FarPlaneTile;
import java.util.*;
import org.lwjgl.system.MemoryUtil;

/** Small, budgeted atlas pages. Slots are released only after tile retirement fences. */
final class PlaneAtlas {
    static final class BudgetUnavailable extends RuntimeException {}
    private static final Map<Integer,LinkedHashSet<Page>> available=new HashMap<>();
    private static void addAvailable(Page page) { available.computeIfAbsent(page.slotSize,k -> new LinkedHashSet<>()).add(page); }
    private static void removeAvailable(Page page) { var set=available.get(page.slotSize); if(set!=null) { set.remove(page); if(set.isEmpty()) available.remove(page.slotSize); } }
    static final class Slot implements AutoCloseable {
        final Page page; final int index,side;
        Slot(Page page,int index,int side) { this.page=page; this.index=index; this.side=side; }
        int x() { return index%page.slotsPerRow*page.slotSize; } int y() { return index/page.slotsPerRow*page.slotSize; }
        public void close() { boolean full=page.used.cardinality()==page.capacity; page.used.clear(index); if(page.used.isEmpty()) { removeAvailable(page); page.view.close(); page.texture.close(); page.memory.close(); } else if(full) addAvailable(page); }
    }
    static final class Page {
        final BitSet used=new BitSet(); final GpuTexture texture; final GpuTextureView view; final ByteBudget.Reservation memory;
        final int slotSize,slotsPerRow,capacity;
        Page(ByteBudget budget,int side) {
            slotSize=Math.max(32,side); int pageSize=Math.max(256,slotSize);
            slotsPerRow=pageSize/slotSize; capacity=slotsPerRow*slotsPerRow;
            memory=budget.tryReserve((long)pageSize*pageSize*4); if(memory==null) throw new BudgetUnavailable();
            GpuTexture allocated=null;
            try { allocated=RenderSystem.getDevice().createTexture("FarAway plane atlas",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM,pageSize,pageSize,1,1); texture=allocated; view=RenderSystem.getDevice().createTextureView(texture); }
            catch(RuntimeException e) { if(allocated!=null) allocated.close(); memory.close(); throw e; }
        }
    }
    static Slot upload(FarPlaneTile tile,ByteBudget budget) {
        int slotSize=Math.max(32,tile.side());
        var candidates=available.get(slotSize);
        Page page=candidates==null?null:candidates.iterator().next();
        if(page==null) { page=new Page(budget,tile.side()); addAvailable(page); }
        int index=page.used.nextClearBit(0); page.used.set(index);
        if(page.used.cardinality()==page.capacity) removeAvailable(page);
        var slot=new Slot(page,index,tile.side());
        var data=MemoryUtil.memAlloc(tile.rgba().length);
        try { data.put(tile.rgba()).flip(); var encoder=RenderSystem.getDevice().createCommandEncoder(); encoder.writeToTexture(page.texture,data,0,0,slot.x(),slot.y(),tile.side(),tile.side()); encoder.submit(); return slot; }
        catch(RuntimeException e) { slot.close(); throw e; } finally { MemoryUtil.memFree(data); }
    }
}
