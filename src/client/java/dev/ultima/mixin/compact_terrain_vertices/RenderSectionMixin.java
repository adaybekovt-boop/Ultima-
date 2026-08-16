package dev.ultima.mixin.compact_terrain_vertices;

import dev.ultima.vertex.CompactTerrainVertex;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin {
    @ModifyVariable(
            method = "addSectionBuffersToUberBuffer",
            at = @At("HEAD"),
            argsOnly = true,
            index = 3)
    private ByteBuffer ultimaConvertOpaqueVertices(
            final ByteBuffer vertexBuffer,
            final ChunkSectionLayer layer,
            final CompiledSectionMesh key) {
        if (vertexBuffer == null || layer.translucent()) {
            return vertexBuffer;
        }
        ByteBuffer compact = CompactTerrainVertex.convertVanillaBlock(vertexBuffer);
        return compact != null ? compact : vertexBuffer;
    }
}
