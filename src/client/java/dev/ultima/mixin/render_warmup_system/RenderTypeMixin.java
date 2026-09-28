package dev.ultima.mixin.render_warmup_system;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.client.warmup.FirstUseProfiler;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(RenderType.class)
public abstract class RenderTypeMixin {
    @WrapMethod(method = "create")
    private static RenderType ultima$profileRenderTypeCreation(
            final String name, final RenderSetup state, final Operation<RenderType> original) {
        FirstUseProfiler.Token token = FirstUseProfiler.begin("render_type", name);
        try {
            return original.call(name, state);
        } finally {
            FirstUseProfiler.end(token);
        }
    }
}
