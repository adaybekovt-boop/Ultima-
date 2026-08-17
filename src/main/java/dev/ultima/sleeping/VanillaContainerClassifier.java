package dev.ultima.sleeping;

import dev.ultima.sleeping.hopper.NeighborKind;
import dev.ultima.sleeping.hopper.NeighborState;
import dev.ultima.sleeping.hopper.Occupancy;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.WorldlyContainerHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * Allowlist of vanilla containers whose item mutations go through hooked
 * {@code Container} methods (or {@code setChanged} after a merge-grow).
 *
 * <p>Exact class match, not {@code instanceof}: a modded subclass is treated as unknown
 * even if it extends a vanilla chest.
 */
public final class VanillaContainerClassifier {
    private VanillaContainerClassifier() {
    }

    public static NeighborState classify(final @Nullable Container container) {
        if (container == null) {
            return NeighborState.absent();
        }
        if (container instanceof CompoundContainer compound) {
            if (!CompoundContainerAccess.installed()) {
                return NeighborState.unknown();
            }
            return classifyCompound(compound);
        }
        if (!isSleepSafeClass(container.getClass())) {
            return NeighborState.unknown();
        }
        if (hasUnresolvedLoot(container)) {
            return new NeighborState(NeighborKind.UNKNOWN, Occupancy.NA, true);
        }
        return NeighborState.sleepSafe(occupancy(container));
    }

    public static boolean isSleepSafeClass(final Class<?> type) {
        return type == HopperBlockEntity.class
                || type == ChestBlockEntity.class
                || type == TrappedChestBlockEntity.class
                || type == BarrelBlockEntity.class
                || type == ShulkerBoxBlockEntity.class
                || type == DispenserBlockEntity.class
                || type == DropperBlockEntity.class;
    }

    public static boolean isWorldlyContainerHolder(final Object block) {
        return block instanceof WorldlyContainerHolder;
    }

    public static Occupancy occupancy(final Container container) {
        boolean any = false;
        boolean allFull = true;
        int size = container.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                allFull = false;
                continue;
            }
            any = true;
            if (stack.getCount() < stack.getMaxStackSize()) {
                allFull = false;
            }
        }
        if (!any) {
            return Occupancy.EMPTY;
        }
        return allFull ? Occupancy.FULL : Occupancy.PARTIAL;
    }

    private static NeighborState classifyCompound(final CompoundContainer compound) {
        Container left = CompoundContainerAccess.left(compound);
        Container right = CompoundContainerAccess.right(compound);
        NeighborState a = classify(left);
        NeighborState b = classify(right);
        if (a.forbidsSleep() || b.forbidsSleep()) {
            return NeighborState.unknown();
        }
        Occupancy occupancy;
        if (a.occupancy() == Occupancy.FULL && b.occupancy() == Occupancy.FULL) {
            occupancy = Occupancy.FULL;
        } else if (a.occupancy() == Occupancy.EMPTY && b.occupancy() == Occupancy.EMPTY) {
            occupancy = Occupancy.EMPTY;
        } else {
            occupancy = Occupancy.PARTIAL;
        }
        return NeighborState.sleepSafe(occupancy);
    }

    private static boolean hasUnresolvedLoot(final Container container) {
        return container instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null;
    }

    /**
     * {@link CompoundContainer} fields are private. Production code installs the accessor mixin.
     * Without it, double chests are classified unknown (vanilla polling).
     */
    public static final class CompoundContainerAccess {
        private static volatile @Nullable Access access;

        private CompoundContainerAccess() {
        }

        public static boolean installed() {
            return access != null;
        }

        public static void install(final Access installed) {
            access = installed;
        }

        static Container left(final CompoundContainer compound) {
            Access installed = access;
            if (installed == null) {
                throw new IllegalStateException("CompoundContainer accessor mixin is not installed");
            }
            return installed.left(compound);
        }

        static Container right(final CompoundContainer compound) {
            Access installed = access;
            if (installed == null) {
                throw new IllegalStateException("CompoundContainer accessor mixin is not installed");
            }
            return installed.right(compound);
        }

        public interface Access {
            Container left(CompoundContainer compound);

            Container right(CompoundContainer compound);
        }
    }
}
