package dev.ultima.mixin.collision_shell_skip;

import dev.ultima.ext.InteriorOnlyCursor;
import java.util.function.Predicate;
import net.minecraft.core.Cursor3D;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A block collision query walks the collider's volume grown by one block in every direction, but
 * only the interior of that volume is tested against normally. The surrounding shell exists solely
 * to catch two things: a block whose collision shape reaches outside its own cube (a fence, a wall),
 * and a moving piston. For every other block a shell position reads a block state and then discards
 * it.
 *
 * <p>Instrumenting a loaded server measured the shell at 87% of all positions visited, so most of
 * the work in the most expensive server-side operation is spent proving that ordinary terrain is
 * ordinary.
 *
 * <p>Whether any block in the queried volume can qualify is a question about the block palettes of
 * the few sections it covers, which {@link LevelChunkSection#maybeHas} answers without touching
 * individual blocks: instantly for a uniform section, and by scanning the palette otherwise. Asking
 * once per query lets the cursor skip the shell outright, so those positions cost nothing rather
 * than a chunk lookup and a block state read each.
 *
 * <p>Nothing is cached beyond a single query, so there is no index that could go stale and let an
 * entity walk through a fence. Anything that cannot be established — a chunk source that is not a
 * level, a debug world, a palette large enough that {@code maybeHas} stops discriminating, or a
 * volume the cursor cannot restrict in the first place — leaves the vanilla path in place.
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsShellMixin {
    /**
     * A block only matters at a shell position if its collision shape can reach outside its own
     * cube, or if it is a moving piston. States with a dynamic shape report {@code true} from
     * {@code hasLargeCollisionShape}, so blocks added by other mods are treated conservatively.
     */
    @Unique
    private static final Predicate<BlockState> ULTIMA_MATTERS_IN_SHELL =
            state -> state.hasLargeCollisionShape() || state.is(Blocks.MOVING_PISTON);

    @Shadow
    @Final
    private AABB box;

    @Shadow
    @Final
    private CollisionGetter collisionGetter;

    @Shadow
    @Final
    private Cursor3D cursor;

    @Inject(
            method = "<init>(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/phys/AABB;ZLjava/util/function/BiFunction;)V",
            at = @At("TAIL"))
    private void ultimaRestrictCursorToInterior(final CallbackInfo ci) {
        /*
         * Ask whether the answer can be used before computing it. The palette scan is bounded by the
         * number of sections the volume covers, which is unbounded in the size of the box: a caller
         * that asks about a region millions of blocks wide would otherwise scan sections eagerly for
         * a cursor that keeps the vanilla traversal anyway, turning a query vanilla answers into one
         * that does not finish. Cheap, constant-time conditions therefore come first.
         */
        if (this.cursor instanceof InteriorOnlyCursor interiorOnly
                && interiorOnly.ultimaCanVisitInteriorOnly()
                && this.ultimaShellIsIrrelevant()) {
            interiorOnly.ultimaVisitInteriorOnly();
        }
    }

    @Unique
    private boolean ultimaShellIsIrrelevant() {
        // A debug world synthesises block states in LevelChunk.getBlockState without consulting the
        // section, so its palettes say nothing about what a position holds.
        if (!(this.collisionGetter instanceof Level level) || level.isDebug()) {
            return false;
        }

        // The same volume the constructor gave to the cursor.
        int minSectionX = (Mth.floor(this.box.minX - 1.0E-7) - 1) >> 4;
        int maxSectionX = (Mth.floor(this.box.maxX + 1.0E-7) + 1) >> 4;
        int minSectionY = (Mth.floor(this.box.minY - 1.0E-7) - 1) >> 4;
        int maxSectionY = (Mth.floor(this.box.maxY + 1.0E-7) + 1) >> 4;
        int minSectionZ = (Mth.floor(this.box.minZ - 1.0E-7) - 1) >> 4;
        int maxSectionZ = (Mth.floor(this.box.maxZ + 1.0E-7) + 1) >> 4;

        for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
            for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                BlockGetter chunkForCollisions = this.collisionGetter.getChunkForCollisions(sectionX, sectionZ);
                if (chunkForCollisions == null) {
                    // Vanilla skips every position in an absent chunk regardless of face type.
                    continue;
                }

                if (!(chunkForCollisions instanceof LevelChunk chunk)) {
                    return false;
                }

                LevelChunkSection[] sections = chunk.getSections();
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    int sectionIndex = chunk.getSectionIndexFromSectionY(sectionY);
                    if (sectionIndex < 0 || sectionIndex >= sections.length) {
                        // Outside the build height, where getBlockState reports air.
                        continue;
                    }

                    if (sections[sectionIndex].maybeHas(ULTIMA_MATTERS_IN_SHELL)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }
}
