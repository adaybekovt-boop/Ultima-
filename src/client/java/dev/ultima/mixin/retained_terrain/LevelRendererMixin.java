package dev.ultima.mixin.retained_terrain;

import dev.ultima.client.renderer.retained.RetainedTerrainRenderer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureManager;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Shadow
    private @Nullable SectionRenderDispatcher sectionRenderDispatcher;

    @Shadow
    @Final
    private TextureManager textureManager;

    @Inject(method = "prepareChunkRenders", at = @At("HEAD"), cancellable = true)
    private void ultimaRetainedPrepare(final Matrix4fc modelViewMatrix, final CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        ChunkSectionsToRender built = RetainedTerrainRenderer.get().prepare(
                (LevelRenderer)(Object)this,
                modelViewMatrix,
                this.visibleSections,
                this.sectionRenderDispatcher,
                this.textureManager);
        if (built != null) {
            cir.setReturnValue(built);
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void ultimaRetainedClose(final CallbackInfo ci) {
        RetainedTerrainRenderer.get().reset();
    }
}
