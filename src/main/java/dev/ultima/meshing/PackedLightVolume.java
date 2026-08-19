package dev.ultima.meshing;

import java.util.Arrays;
import net.minecraft.util.LightCoordsUtil;

/**
 * Packed sky/block light for the 18³ halo. Test/kernel only; production lighting
 * stays on vanilla {@code BlockModelLighter}.
 */
public final class PackedLightVolume {
    private final int[] lights = new int[SectionIndex.VOLUME];

    public PackedLightVolume() {
        Arrays.fill(this.lights, LightCoordsUtil.pack(15, 15));
    }

    public void fill(final int packedLight) {
        Arrays.fill(this.lights, packedLight);
    }

    public void set(final int packedIndex, final int packedLight) {
        this.lights[packedIndex] = packedLight;
    }

    public int get(final int packedIndex) {
        return this.lights[packedIndex];
    }

    public void setInterior(final int x, final int y, final int z, final int packedLight) {
        this.lights[SectionIndex.interior(x, y, z)] = packedLight;
    }
}
