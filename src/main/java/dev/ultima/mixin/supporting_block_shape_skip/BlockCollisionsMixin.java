package dev.ultima.mixin.supporting_block_shape_skip;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ultima.ext.DiscardedShapeCollisions;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Full-cube candidates in {@code BlockCollisions.computeNext} already test
 * {@code AABB.intersects} before calling {@code VoxelShape.move}. When the caller is
 * {@code findSupportingBlock}, that moved shape is thrown away, so returning the shared full cube
 * is equivalent and removes the per-candidate {@code ArrayVoxelShape} plus three offset lists.
 *
 * <p>Non-cube shapes still have to be moved because {@code Shapes.joinIsNotEmpty} needs the
 * world-space outline.
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsMixin implements DiscardedShapeCollisions {
    @Unique
    private boolean ultimaDiscardReturnedShape;

    @Override
    public void ultimaDiscardReturnedShape() {
        this.ultimaDiscardReturnedShape = true;
    }

    @Override
    public boolean ultimaIsReturnedShapeDiscarded() {
        return this.ultimaDiscardReturnedShape;
    }

    @WrapOperation(
            method = "computeNext",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/VoxelShape;move(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape ultimaSkipUnusedFullCubeMove(
            final VoxelShape shape,
            final Vec3i pos,
            final Operation<VoxelShape> original) {
        if (this.ultimaDiscardReturnedShape && shape == Shapes.block()) {
            return shape;
        }
        return original.call(shape, pos);
    }
}
