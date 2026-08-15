package dev.ultima.mixin.full_cube_move;

import dev.ultima.phys.OffsetCubeVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code Shapes.block().move(BlockPos)} is the common collision hit: a full cube shifted to world
 * coordinates. Vanilla always allocates an {@code ArrayVoxelShape} wrapping three offset lists even
 * though the result is uniquely determined by three ints.
 *
 * <p>Only the shared full-cube instance is rewritten, and only when the offset is an exact integer,
 * which is the {@code VoxelShape.move(Vec3i)} path {@code BlockCollisions} uses. Non-cube shapes and
 * fractional moves keep the vanilla allocating implementation.
 */
@Mixin(VoxelShape.class)
public abstract class VoxelShapeMixin {
    @Inject(
            method = "move(DDD)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"),
            cancellable = true)
    private void ultimaMoveFullCube(
            final double dx,
            final double dy,
            final double dz,
            final CallbackInfoReturnable<VoxelShape> cir) {
        if ((Object)this != Shapes.block()
                || !OffsetCubeVoxelShape.isExactInt(dx)
                || !OffsetCubeVoxelShape.isExactInt(dy)
                || !OffsetCubeVoxelShape.isExactInt(dz)) {
            return;
        }

        cir.setReturnValue(new OffsetCubeVoxelShape((int)dx, (int)dy, (int)dz));
    }
}
