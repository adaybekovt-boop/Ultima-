package dev.ultima.cache;

/**
 * Conservative purity gate: only {@code net.minecraft.*} implementations are treated as audited
 * vanilla. Any other package (modded blocks/fluids, anonymous helpers outside vanilla) falls back
 * to the vanilla call on every query.
 */
public final class VanillaClassGuard {
    private VanillaClassGuard() {
    }

    public static boolean isVanillaType(final Object instance) {
        if (instance == null) {
            return false;
        }
        return isVanillaClass(instance.getClass());
    }

    public static boolean isVanillaClass(final Class<?> type) {
        if (type == null) {
            return false;
        }
        String name = type.getName();
        return name.startsWith("net.minecraft.") && name.indexOf('/', 0) < 0;
    }
}
