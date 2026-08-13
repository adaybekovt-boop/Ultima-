package dev.ultima.mixin.block_collision_shape;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every block collision query voxelises the collider's bounding box up front, but that shape is only
 * needed to intersect against a block whose collision shape is neither empty nor a full cube. An
 * entity moving through air and full blocks never reaches that branch, so the shape is built and
 * thrown away.
 *
 * <p>The voxelisation is deferred to the first read instead. The shape is produced by the same call
 * on the same immutable box, so the value observed by the intersection test is unchanged.
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsMixin {
    @Shadow
    @Final
    private AABB box;

    @Unique
    private @Nullable VoxelShape ultimaEntityShape;

    @ModifyExpressionValue(
            method = "<init>(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/phys/AABB;ZLjava/util/function/BiFunction;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/Shapes;create(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape ultimaSkipEagerVoxelisation(final VoxelShape original) {
        return null;
    }

    @ModifyExpressionValue(
            method = "computeNext",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/BlockCollisions;entityShape:Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape ultimaVoxeliseOnDemand(final VoxelShape original) {
        if (original != null) {
            return original;
        }

        VoxelShape shape = this.ultimaEntityShape;
        if (shape == null) {
            shape = Shapes.create(this.box);
            this.ultimaEntityShape = shape;
        }

        return shape;
    }
}
