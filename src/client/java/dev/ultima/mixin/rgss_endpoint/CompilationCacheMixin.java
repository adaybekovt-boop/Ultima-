package dev.ultima.mixin.rgss_endpoint;

import com.mojang.blaze3d.shaders.ShaderType;
import dev.ultima.client.renderer.rgss.RgssEndpointSpecializer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$CompilationCache")
public abstract class CompilationCacheMixin {
    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private void ultimaSpecializeRgss(final Identifier id, final ShaderType type, final CallbackInfoReturnable<String> cir) {
        if (type != ShaderType.FRAGMENT) {
            return;
        }
        String path = id.getPath();
        if (!"core/terrain".equals(path) && !"core/terrain_retained".equals(path)) {
            return;
        }
        String specialized = RgssEndpointSpecializer.specialize(cir.getReturnValue());
        if (specialized != null && specialized != cir.getReturnValue()) {
            cir.setReturnValue(specialized);
        }
    }
}
