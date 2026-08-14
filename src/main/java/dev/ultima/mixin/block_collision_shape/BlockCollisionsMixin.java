package dev.ultima.mixin.block_collision_shape;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
 *
 * <p>The eager call is suppressed with {@code @WrapOperation} rather than
 * {@code @ModifyExpressionValue}: the latter only replaces the value an expression produced, so
 * {@code Shapes.create} would still run and still allocate, and only its result would be thrown
 * away. Wrapping the operation and declining to call it is what actually removes the work. This
 * costs nothing extra because the wrapper never invokes {@link Operation#call}, so no varargs array
 * is created — and it happens once per query rather than once per block position.
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsMixin {
    @Shadow
    @Final
    private AABB box;

    @Unique
    private @Nullable VoxelShape ultimaEntityShape;

    @WrapOperation(
            method = "<init>(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/phys/AABB;ZLjava/util/function/BiFunction;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/Shapes;create(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape ultimaSkipEagerVoxelisation(final AABB box, final Operation<VoxelShape> original) {
        // Deliberately does not call `original`: the whole point is that this query may never need
        // the shape. `ultimaVoxeliseOnDemand` performs the same call on the same box if it does.
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
