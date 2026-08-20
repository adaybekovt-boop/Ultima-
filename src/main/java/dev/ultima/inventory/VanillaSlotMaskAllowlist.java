package dev.ultima.inventory;

import java.util.Set;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.boat.ChestBoat;
import net.minecraft.world.entity.vehicle.boat.ChestRaft;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;

/**
 * Exact-class allowlist for consuming a slot mask as a skip filter.
 *
 * <p>Tracking mixins still run on the vanilla base types (so a modded chest subclass keeps a
 * mask), but consumption fails open to a full vanilla scan for any class that is not listed here.
 * Unresolved loot tables also fail open: {@code getItem} unpacks loot, and skipping that call
 * would hide generated items.
 */
public final class VanillaSlotMaskAllowlist {
    private static final Set<Class<?>> ALLOWED = Set.of(
            SimpleContainer.class,
            PlayerEnderChestContainer.class,
            CompoundContainer.class,
            Inventory.class,
            ChestBlockEntity.class,
            TrappedChestBlockEntity.class,
            BarrelBlockEntity.class,
            ShulkerBoxBlockEntity.class,
            HopperBlockEntity.class,
            DispenserBlockEntity.class,
            DropperBlockEntity.class,
            FurnaceBlockEntity.class,
            BlastFurnaceBlockEntity.class,
            SmokerBlockEntity.class,
            BrewingStandBlockEntity.class,
            CrafterBlockEntity.class,
            ChiseledBookShelfBlockEntity.class,
            ShelfBlockEntity.class,
            MinecartChest.class,
            MinecartHopper.class,
            ChestBoat.class,
            ChestRaft.class);

    private static final ThreadLocal<Class<?>> EXTRA_ALLOWED = new ThreadLocal<>();

    private VanillaSlotMaskAllowlist() {
    }

    public static boolean isAllowedClass(final Class<?> type) {
        return ALLOWED.contains(type) || type == EXTRA_ALLOWED.get();
    }

    /**
     * Test-only: allow a {@link SimpleContainer} subclass so
     * {@link SlotMaskQueries} can consume a throwing mock through the
     * production allowlist gate. Exact-class matching otherwise rejects mocks.
     */
    public static void runAllowing(final Class<?> type, final Runnable action) {
        Class<?> previous = EXTRA_ALLOWED.get();
        EXTRA_ALLOWED.set(type);
        try {
            action.run();
        } finally {
            if (previous == null) {
                EXTRA_ALLOWED.remove();
            } else {
                EXTRA_ALLOWED.set(previous);
            }
        }
    }

    public static boolean mayConsume(final Container container) {
        if (container == null || !isAllowedClass(container.getClass())) {
            return false;
        }
        if (container instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null) {
            return false;
        }
        if (container instanceof CompoundContainer compound) {
            Container left = CompoundContainerViews.left(compound);
            Container right = CompoundContainerViews.right(compound);
            if (left == null || right == null) {
                return false;
            }
            return mayConsume(left) && mayConsume(right);
        }
        return true;
    }
}
