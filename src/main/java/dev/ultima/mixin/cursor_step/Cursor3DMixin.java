package dev.ultima.mixin.cursor_step;

import net.minecraft.core.Cursor3D;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 */
@Mixin(Cursor3D.class)
public abstract class Cursor3DMixin {
    @Shadow
    @Final
    private int width;

    @Shadow
    @Final
    private int height;

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

    @Inject(method = "advance", at = @At("HEAD"), cancellable = true)
    private void ultimaAdvanceWithoutDividing(final CallbackInfoReturnable<Boolean> cir) {
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
}
