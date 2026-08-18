package dev.ultima.sleeping.hopper;

import dev.ultima.sleeping.VanillaContainerClassifier;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads a live hopper and its neighbours into a {@link HopperSleepSnapshot} without
 * unpacking loot tables (loot presence is a refuse condition).
 */
public final class HopperWorldInspector {
    private HopperWorldInspector() {
    }

    public static HopperSleepSnapshot inspect(
            final Level level, final BlockPos pos, final BlockState state, final HopperBlockEntity hopper) {
        boolean exact = hopper.getClass() == HopperBlockEntity.class;
        boolean enabled = state.getValue(HopperBlock.ENABLED);
        Direction facing = state.getValue(HopperBlock.FACING);
        boolean unresolvedOwnLoot = hopper instanceof RandomizableContainerBlockEntity randomizable
                && randomizable.getLootTable() != null;
        boolean ownEmpty = unresolvedOwnLoot || hopperEmptyWithoutUnpack(hopper);
        boolean ownFull = !unresolvedOwnLoot && hopperFullWithoutUnpack(hopper);

        BlockPos sourcePos = pos.above();
        BlockPos destPos = pos.relative(facing);
        BlockState sourceState = level.getBlockState(sourcePos);
        NeighborState source = classifyAt(level, sourcePos, sourceState);
        NeighborState dest = classifyAt(level, destPos, level.getBlockState(destPos));

        boolean pickupBlocked = hopper.isGridAligned()
                && sourceState.isCollisionShapeFullBlock(level, sourcePos)
                && !sourceState.is(BlockTags.DOES_NOT_BLOCK_HOPPERS);

        boolean itemsInSuck = false;
        if (source.kind() == NeighborKind.ABSENT && !ownFull && !pickupBlocked) {
            List<ItemEntity> items = HopperBlockEntity.getItemsAtAndAbove(level, hopper);
            for (ItemEntity item : items) {
                if (!item.getItem().isEmpty()) {
                    itemsInSuck = true;
                    break;
                }
            }
        }

        int cooldown = HopperCooldownAccess.get(hopper);
        return new HopperSleepSnapshot(
                exact,
                enabled,
                cooldown,
                ownEmpty,
                ownFull,
                unresolvedOwnLoot,
                source,
                dest,
                pickupBlocked,
                itemsInSuck);
    }

    public static long[] watchPositions(final BlockPos pos, final BlockState state) {
        Direction facing = state.getValue(HopperBlock.FACING);
        return new long[] {pos.asLong(), pos.above().asLong(), pos.relative(facing).asLong()};
    }

    private static NeighborState classifyAt(final Level level, final BlockPos pos, final BlockState state) {
        if (VanillaContainerClassifier.isWorldlyContainerHolder(state.getBlock())) {
            return NeighborState.unknown();
        }
        Container container = HopperBlockEntity.getContainerAt(level, pos);
        return VanillaContainerClassifier.classify(container);
    }

    private static boolean hopperEmptyWithoutUnpack(final HopperBlockEntity hopper) {
        int size = hopper.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = HopperItemAccess.getRaw(hopper, slot);
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hopperFullWithoutUnpack(final HopperBlockEntity hopper) {
        int size = hopper.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = HopperItemAccess.getRaw(hopper, slot);
            if (stack.isEmpty() || stack.getCount() != stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Installed by the hopper accessor mixin so eligibility checks never call {@code getItem}
     * (which unpacks loot).
     */
    public static final class HopperItemAccess {
        private static volatile Access access = (hopper, slot) -> hopper.getItem(slot);

        private HopperItemAccess() {
        }

        public static void install(final Access installed) {
            access = installed;
        }

        static ItemStack getRaw(final HopperBlockEntity hopper, final int slot) {
            return access.get(hopper, slot);
        }

        public interface Access {
            ItemStack get(HopperBlockEntity hopper, int slot);
        }
    }

    public static final class HopperCooldownAccess {
        private static volatile IntAccess access = hopper -> 0;

        private HopperCooldownAccess() {
        }

        public static void install(final IntAccess installed) {
            access = installed;
        }

        static int get(final HopperBlockEntity hopper) {
            return access.get(hopper);
        }

        public interface IntAccess {
            int get(HopperBlockEntity hopper);
        }
    }
}
