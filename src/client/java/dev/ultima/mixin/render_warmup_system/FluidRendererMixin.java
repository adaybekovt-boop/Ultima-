package dev.ultima.mixin.render_warmup_system;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.client.warmup.FirstUseProfiler;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {
    @Unique private static volatile boolean ultima$profiledFirstFluid;

    @WrapMethod(method = "tesselate")
    private void ultima$profileFirstFluidTessellation(
            final BlockAndTintGetter level,
            final BlockPos pos,
            final FluidRenderer.Output output,
            final BlockState blockState,
            final FluidState fluidState,
            final Operation<Void> original) {
        if (ultima$profiledFirstFluid) {
            original.call(level, pos, output, blockState, fluidState);
            return;
        }
        FirstUseProfiler.Token token = FirstUseProfiler.begin("fluid_renderer", "first_tessellation");
        try {
            original.call(level, pos, output, blockState, fluidState);
        } finally {
            FirstUseProfiler.end(token);
            ultima$profiledFirstFluid = true;
        }
    }
}
