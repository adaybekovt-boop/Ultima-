package dev.ultima.client.renderer.vertex;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ultima.config.UltimaConfig;
import dev.ultima.lab.LabFeatureGates;
import dev.ultima.vertex.CompactTerrainVertex;

/**
 * Backend-neutral compact terrain {@link VertexFormat}. Does not mutate
 * {@code DefaultVertexFormat.BLOCK}.
 */
public final class CompactTerrainVertexFormat {
    public static final VertexFormat FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGBA16_UNORM)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("UV0", GpuFormat.RG16_UNORM)
            .addAttribute("UV2", GpuFormat.RG16_SINT)
            .build();

    private CompactTerrainVertexFormat() {
    }

    public static boolean gpuPathEnabled() {
        return UltimaConfig.get().isEnabled(LabFeatureGates.COMPACT_TERRAIN_VERTICES);
    }

    public static int opaqueStride(final VertexFormat vanilla) {
        return gpuPathEnabled() ? CompactTerrainVertex.BYTES : vanilla.getVertexSize();
    }
}
