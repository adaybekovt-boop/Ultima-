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
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsMixin {
    @Shadow
    @Final
    private AABB box;

    @Unique
    private @Nullable VoxelShape ultimaEntityShape;

    @Unique
    private @Nullable Operation<VoxelShape> ultimaEntityShapeFactory;

    @WrapOperation(
            method = "<init>(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/phys/AABB;ZLjava/util/function/BiFunction;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/Shapes;create(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private @Nullable VoxelShape ultimaSkipEagerVoxelisation(final AABB box, final Operation<VoxelShape> original) {
        /*
         * Retain the complete wrapped operation chain, not merely Shapes.create itself. Calling it
         * on demand lets lower/inner MixinExtras wrappers still participate. Wrappers outside this
         * handler necessarily observe the constructor-time null sentinel, so this module remains
         * opt-in: perfect composition is impossible while also suppressing the eager call.
         */
        this.ultimaEntityShapeFactory = original;
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
            Operation<VoxelShape> factory = this.ultimaEntityShapeFactory;
            try {
                shape = factory == null ? Shapes.create(this.box) : factory.call(this.box);
            } finally {
                // Do not retain captures from this constructor call for the iterator's lifetime.
                this.ultimaEntityShapeFactory = null;
            }
            this.ultimaEntityShape = shape;
        }

        return shape;
    }
}
