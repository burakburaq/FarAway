package dev.faraway.k7q4m9.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.faraway.k7q4m9.diagnostics.WorldgenProfiler;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.OreVeinRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(OreVeinRule.class)
abstract class OreVeinProfilingMixin {
    @WrapMethod(method="compile")
    private RuleEvaluator faraway$profileOreVeins(MaterialRuleContext context,Operation<RuleEvaluator> original) {
        var rule=original.call(context);
        if(!WorldgenProfiler.measuring()) return rule;
        return (x,y,z) -> {
            long start=System.nanoTime();
            try { return rule.tryApply(x,y,z); }
            finally { WorldgenProfiler.oreVein(System.nanoTime()-start); }
        };
    }
}
