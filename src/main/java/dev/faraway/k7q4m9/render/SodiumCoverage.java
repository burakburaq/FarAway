package dev.faraway.k7q4m9.render;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.*;
import java.util.Iterator;
import net.minecraft.core.SectionPos;

/** Read-only adapter for the installed Sodium 0.9.2 render lists. */
final class SodiumCoverage {
    private final Method instance,lists,iterator,region,geometry,hasNext,next,x,y,z,ux,uy,uz;
    private final Field manager;
    SodiumCoverage() {
        try {
            String root="net.caffeinemc.mods.sodium.client.";
            var renderer=Class.forName(root+"render.SodiumWorldRenderer");
            instance=renderer.getMethod("instanceNullable");
            manager=renderer.getDeclaredField("renderSectionManager");manager.setAccessible(true);
            lists=Class.forName(root+"render.chunk.RenderSectionManager").getMethod("getRenderLists");
            iterator=Class.forName(root+"render.chunk.lists.SortedRenderLists").getMethod("iterator",boolean.class);
            var list=Class.forName(root+"render.chunk.lists.ChunkRenderList");
            region=list.getMethod("getRegion");geometry=list.getMethod("sectionsWithGeometryIterator",boolean.class);
            var bytes=Class.forName(root+"util.iterator.ByteIterator");hasNext=bytes.getMethod("hasNext");next=bytes.getMethod("nextByteAsInt");
            var r=Class.forName(root+"render.chunk.region.RenderRegion");x=r.getMethod("getChunkX");y=r.getMethod("getChunkY");z=r.getMethod("getChunkZ");
            var index=Class.forName(root+"render.chunk.LocalSectionIndex");ux=index.getMethod("unpackX",int.class);uy=index.getMethod("unpackY",int.class);uz=index.getMethod("unpackZ",int.class);
        } catch(ReflectiveOperationException e) {throw new IllegalStateException("Unsupported Sodium coverage API",e);}
    }
    void collect(LongOpenHashSet sections) {
        sections.clear();
        try {
            var renderer=instance.invoke(null);if(renderer==null)return;
            var m=manager.get(renderer);if(m==null)return;
            var it=(Iterator<?>)iterator.invoke(lists.invoke(m),false);
            while(it.hasNext()) {
                var list=it.next();var r=region.invoke(list);var bytes=geometry.invoke(list,false);
                if(bytes==null)continue;
                int bx=(int)x.invoke(r),by=(int)y.invoke(r),bz=(int)z.invoke(r);
                while((boolean)hasNext.invoke(bytes)) {
                    int i=(int)next.invoke(bytes);
                    sections.add(SectionPos.asLong(bx+(int)ux.invoke(null,i),by+(int)uy.invoke(null,i),bz+(int)uz.invoke(null,i)));
                }
            }
        } catch(ReflectiveOperationException e) {throw new IllegalStateException("Cannot read Sodium draw lists",e);}
    }
}
