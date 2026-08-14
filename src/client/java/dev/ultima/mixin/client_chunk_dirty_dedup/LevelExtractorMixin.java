package dev.ultima.mixin.client_chunk_dirty_dedup;

import dev.ultima.client.benchmark.ClientOptimizationCounters;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla expands block dirtiness by one block, then invokes the same section-dirty write for every
 * block coordinate in that volume. Away from a section boundary, a single block change performs 27
 * identical storage lookups/writes. Iterating the unique section-coordinate range preserves the
 * exact dirty-section set and first-encounter order without postponing any required rebuild.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Shadow
    private void setSectionDirty(
            final int sectionX,
            final int sectionY,
            final int sectionZ,
            final boolean playerChanged) {
        throw new AssertionError();
    }

    @Inject(
            method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V",
            at = @At("HEAD"),
            cancellable = true)
    private void ultimaSetUniqueSectionsDirty(
            final BlockPos pos,
            final boolean playerChanged,
            final CallbackInfo ci) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        if (x == Integer.MIN_VALUE || x == Integer.MAX_VALUE
                || y == Integer.MIN_VALUE || y == Integer.MAX_VALUE
                || z == Integer.MIN_VALUE || z == Integer.MAX_VALUE) {
            return;
        }

        int minSectionX = SectionPos.blockToSectionCoord(x - 1);
        int minSectionY = SectionPos.blockToSectionCoord(y - 1);
        int minSectionZ = SectionPos.blockToSectionCoord(z - 1);
        int maxSectionX = SectionPos.blockToSectionCoord(x + 1);
        int maxSectionY = SectionPos.blockToSectionCoord(y + 1);
        int maxSectionZ = SectionPos.blockToSectionCoord(z + 1);
        int uniqueSections = (maxSectionX - minSectionX + 1)
                * (maxSectionY - minSectionY + 1)
                * (maxSectionZ - minSectionZ + 1);
        ClientOptimizationCounters.avoidedSectionDirtyWrites(27L - uniqueSections);
        this.ultimaSetSectionRangeDirty(
                minSectionX,
                minSectionY,
                minSectionZ,
                maxSectionX,
                maxSectionY,
                maxSectionZ,
                playerChanged);
        ci.cancel();
    }

    @Inject(method = "setBlocksDirty", at = @At("HEAD"), cancellable = true)
    private void ultimaSetUniqueSectionRangeDirty(
            final int x0,
            final int y0,
            final int z0,
            final int x1,
            final int y1,
            final int z1,
            final CallbackInfo ci) {
        if (x0 > x1 || y0 > y1 || z0 > z1
                || x0 == Integer.MIN_VALUE || y0 == Integer.MIN_VALUE || z0 == Integer.MIN_VALUE
                || x1 == Integer.MAX_VALUE || y1 == Integer.MAX_VALUE || z1 == Integer.MAX_VALUE) {
            return;
        }

        this.ultimaSetSectionRangeDirty(
                SectionPos.blockToSectionCoord(x0 - 1),
                SectionPos.blockToSectionCoord(y0 - 1),
                SectionPos.blockToSectionCoord(z0 - 1),
                SectionPos.blockToSectionCoord(x1 + 1),
                SectionPos.blockToSectionCoord(y1 + 1),
                SectionPos.blockToSectionCoord(z1 + 1),
                false);
        ci.cancel();
    }

    @Unique
    private void ultimaSetSectionRangeDirty(
            final int minSectionX,
            final int minSectionY,
            final int minSectionZ,
            final int maxSectionX,
            final int maxSectionY,
            final int maxSectionZ,
            final boolean playerChanged) {
        for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
            for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    this.setSectionDirty(sectionX, sectionY, sectionZ, playerChanged);
                }
            }
        }
    }
}
