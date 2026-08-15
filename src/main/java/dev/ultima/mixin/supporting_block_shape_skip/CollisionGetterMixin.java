package dev.ultima.mixin.supporting_block_shape_skip;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ultima.ext.DiscardedShapeCollisions;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code findSupportingBlock} walks every colliding block but keeps only the {@code BlockPos}. The
 * moved voxel shape is allocated and discarded. Mark that query so {@code computeNext} can skip
 * the unused full-cube {@code VoxelShape.move} without changing which positions are visited.
 */
@Mixin(CollisionGetter.class)
public interface CollisionGetterMixin {
    @WrapOperation(
            method = "findSupportingBlock",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;ZLjava/util/function/BiFunction;)Lnet/minecraft/world/level/BlockCollisions;"))
    default <T> BlockCollisions<T> ultimaMarkSupportingBlockQuery(
            final CollisionGetter collisionGetter,
            final Entity source,
            final AABB box,
            final boolean onlySuffocatingBlocks,
            final BiFunction<BlockPos.MutableBlockPos, VoxelShape, T> resultProvider,
            final Operation<BlockCollisions<T>> original) {
        BlockCollisions<T> collisions = original.call(
                collisionGetter, source, box, onlySuffocatingBlocks, resultProvider);
        if (collisions instanceof DiscardedShapeCollisions discarded) {
            discarded.ultimaDiscardReturnedShape();
        }
        return collisions;
    }
}
