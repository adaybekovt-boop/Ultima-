package dev.ultima.mixin.cursor_step;

import dev.ultima.ext.InteriorOnlyCursor;
import net.minecraft.core.Cursor3D;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every step of a block collision query re-derives the cursor's x, y and z from a running index with
 * integer divisions by the volume's width and height. Those divisors are not constant, so they stay
 * as hardware divides in the innermost loop of the most expensive server-side operation there is.
 *
 * <p>The cursor only ever moves forward one position at a time, so the same coordinates can be
 * produced by carrying an increment, which yields exactly the same sequence without dividing.
 *
 * <p>The same carry also makes it cheap to visit only the interior of the volume. A collision query
 * that has established that the surrounding shell cannot contribute anything asks for that through
 * {@link InteriorOnlyCursor}, and the skipped positions then cost nothing at all rather than a chunk
 * lookup and a block state read each.
 */
@Mixin(Cursor3D.class)
public abstract class Cursor3DMixin implements InteriorOnlyCursor {
    @Shadow
    @Final
    private int width;

    @Shadow
    @Final
    private int height;

    @Shadow
    @Final
    private int depth;

    @Shadow
    @Final
    private int end;

    @Shadow
    private int index;

    @Shadow
    private int x;

    @Shadow
    private int y;

    @Shadow
    private int z;

    @Unique
    private boolean ultimaInteriorOnly;

    @Unique
    private boolean ultimaInteriorStarted;

    @Unique
    private boolean ultimaInteriorExhausted;

    @Override
    public void ultimaVisitInteriorOnly() {
        this.ultimaInteriorOnly = true;
    }

    @Inject(method = "advance", at = @At("HEAD"), cancellable = true)
    private void ultimaAdvanceWithoutDividing(final CallbackInfoReturnable<Boolean> cir) {
        if (this.ultimaInteriorOnly) {
            cir.setReturnValue(this.ultimaAdvanceInterior());
            return;
        }

        if (this.index == this.end) {
            cir.setReturnValue(false);
            return;
        }

        // The first position is the origin, which the fields already hold.
        if (this.index != 0) {
            if (++this.x == this.width) {
                this.x = 0;
                if (++this.y == this.height) {
                    this.y = 0;
                    this.z++;
                }
            }
        }

        this.index++;
        cir.setReturnValue(true);
    }

    /**
     * Emits the positions with no coordinate on a face of the volume, in the same order as the full
     * traversal. {@code getNextType} keeps reporting {@code TYPE_INSIDE} for them without changes,
     * because none of their coordinates sits at 0 or at a maximum.
     */
    @Unique
    private boolean ultimaAdvanceInterior() {
        if (this.ultimaInteriorExhausted) {
            return false;
        }

        if (!this.ultimaInteriorStarted) {
            if (this.width < 3 || this.height < 3 || this.depth < 3) {
                this.ultimaInteriorExhausted = true;
                return false;
            }

            this.ultimaInteriorStarted = true;
            this.x = 1;
            this.y = 1;
            this.z = 1;
            this.index++;
            return true;
        }

        if (++this.x == this.width - 1) {
            this.x = 1;
            if (++this.y == this.height - 1) {
                this.y = 1;
                if (++this.z == this.depth - 1) {
                    this.ultimaInteriorExhausted = true;
                    return false;
                }
            }
        }

        this.index++;
        return true;
    }
}
