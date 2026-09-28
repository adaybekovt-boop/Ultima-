package dev.ultima.mixin.render_warmup_system;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.client.warmup.FirstUseProfiler;
import java.util.Map;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EntityRenderers.class)
public abstract class EntityRenderersMixin {
    @WrapMethod(method = "createEntityRenderers")
    private static Map<EntityType<?>, EntityRenderer<?, ?>> ultima$profileRendererFactory(
            final EntityRendererProvider.Context context,
            final Operation<Map<EntityType<?>, EntityRenderer<?, ?>>> original) {
        FirstUseProfiler.Token token = FirstUseProfiler.begin("entity_renderer", "factory_all");
        try {
            return original.call(context);
        } finally {
            FirstUseProfiler.end(token);
        }
    }
}
