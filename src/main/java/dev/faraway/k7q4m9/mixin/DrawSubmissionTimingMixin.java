package dev.faraway.k7q4m9.mixin;
import dev.faraway.k7q4m9.diagnostics.AdvancedRenderTiming;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Times actual backend draw submission for the entire world-render interval. */
@Mixin(targets="com.mojang.renderpearl.backend.opengl.GlRenderPass",remap=false)
public abstract class DrawSubmissionTimingMixin {
    @Inject(method={"draw","drawIndexed","multiDraw","multiDrawIndexed","drawIndirect","drawIndexedIndirect"},at=@At("HEAD"))
    private void farawaySubmissionStart(CallbackInfo ci){AdvancedRenderTiming.beginSubmission();}
    @Inject(method={"draw","drawIndexed","multiDraw","multiDrawIndexed","drawIndirect","drawIndexedIndirect"},at=@At("RETURN"))
    private void farawaySubmissionEnd(CallbackInfo ci){AdvancedRenderTiming.endSubmission();}
}
