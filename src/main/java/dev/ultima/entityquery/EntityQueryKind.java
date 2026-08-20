package dev.ultima.entityquery;

/**
 * Which section counter an empty early-out is allowed to consult.
 *
 * <p>{@link #UNKNOWN} never skips the vanilla query. {@link #COLLIDABLE} is an explicit
 * alias of {@link #ANY}: {@code isPickable}/{@code canBeHitByProjectile} are instance
 * state and are not tracked, so a collidable query may early-out only when the section has no
 * entities at all. {@link #ofQueryClass(Class)} never returns {@code COLLIDABLE}; callers that
 * know they are asking a collidable question pass it explicitly and share the total counter.
 */
public enum EntityQueryKind {
    ANY,
    PLAYERS,
    LIVING,
    ITEMS,
    COLLIDABLE,
    UNKNOWN;

    public static EntityQueryKind ofQueryClass(final Class<?> queryClass) {
        if (queryClass == null) {
            return UNKNOWN;
        }
        String name = queryClass.getName();
        if ("net.minecraft.world.entity.player.Player".equals(name)
                || "net.minecraft.server.level.ServerPlayer".equals(name)
                || "net.minecraft.client.player.AbstractClientPlayer".equals(name)
                || "net.minecraft.client.player.LocalPlayer".equals(name)
                || "net.minecraft.client.player.RemotePlayer".equals(name)) {
            return PLAYERS;
        }
        if ("net.minecraft.world.entity.item.ItemEntity".equals(name)) {
            return ITEMS;
        }
        if ("net.minecraft.world.entity.LivingEntity".equals(name)
                || "net.minecraft.world.entity.Mob".equals(name)
                || "net.minecraft.world.entity.PathfinderMob".equals(name)
                || "net.minecraft.world.entity.AgeableMob".equals(name)
                || "net.minecraft.world.entity.animal.Animal".equals(name)
                || "net.minecraft.world.entity.monster.Monster".equals(name)
                || "net.minecraft.world.entity.npc.villager.AbstractVillager".equals(name)) {
            return LIVING;
        }
        if ("net.minecraft.world.entity.Entity".equals(name)
                || "net.minecraft.world.level.entity.EntityAccess".equals(name)) {
            return ANY;
        }
        if (isAssignableFromLoaded(queryClass, "net.minecraft.world.entity.player.Player")) {
            return PLAYERS;
        }
        if (isAssignableFromLoaded(queryClass, "net.minecraft.world.entity.item.ItemEntity")) {
            return ITEMS;
        }
        if (isAssignableFromLoaded(queryClass, "net.minecraft.world.entity.LivingEntity")) {
            return LIVING;
        }
        if (isAssignableFromLoaded(queryClass, "net.minecraft.world.entity.Entity")) {
            return ANY;
        }
        return UNKNOWN;
    }

    private static boolean isAssignableFromLoaded(final Class<?> queryClass, final String superName) {
        Class<?> cursor = queryClass;
        while (cursor != null) {
            if (superName.equals(cursor.getName())) {
                return true;
            }
            cursor = cursor.getSuperclass();
        }
        return false;
    }
}
