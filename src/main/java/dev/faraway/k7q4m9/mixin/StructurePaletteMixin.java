package dev.faraway.k7q4m9.mixin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Structure templates are shared by world-generation workers. */
@Mixin(StructureTemplate.Palette.class)
public abstract class StructurePaletteMixin {
    @Shadow @Final @Mutable private Map<Block,List<StructureTemplate.StructureBlockInfo>> cache;
    @Inject(method="<init>",at=@At("RETURN"))
    private void faraway$concurrentCache(List<StructureTemplate.StructureBlockInfo> blocks,CallbackInfo ci) {
        cache=new ConcurrentHashMap<>();
    }
}
