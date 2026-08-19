package dev.ultima.inventory;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import org.jspecify.annotations.Nullable;

/**
 * Installed by {@code CompoundContainerAccessor} when the {@code container_slot_mask} module is
 * enabled. Without it, double chests fail open to vanilla scans.
 */
public final class CompoundContainerViews {
    private static volatile @Nullable Access access;

    private CompoundContainerViews() {
    }

    public static void install(final Access installed) {
        access = installed;
    }

    public static boolean installed() {
        return access != null;
    }

    static @Nullable Container left(final CompoundContainer compound) {
        Access installed = access;
        return installed == null ? null : installed.left(compound);
    }

    static @Nullable Container right(final CompoundContainer compound) {
        Access installed = access;
        return installed == null ? null : installed.right(compound);
    }

    public interface Access {
        Container left(CompoundContainer compound);

        Container right(CompoundContainer compound);
    }
}
