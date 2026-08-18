package dev.ultima.entityquery;

import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityTypeTest;

/**
 * Maps a typed entity query onto {@link EntityQueryKind} and maps an entity onto counter deltas.
 *
 * <p>{@code EntityType.getBaseClass()} is {@code Entity.class} in 26.2 (exact-type {@code tryCast}),
 * so those queries only prove emptiness when <em>no</em> entities are present ({@link EntityQueryKind#ANY}).
 * {@code EntityTypeTest.forClass} queries expose the real search class and can use the tighter
 * player / living / item counters.
 */
public final class EntityQueryClassifier {
    private EntityQueryClassifier() {
    }

    public static EntityQueryKind classify(final EntityTypeTest<?, ?> type) {
        if (type == null) {
            return EntityQueryKind.UNKNOWN;
        }
        return classifyClass(type.getBaseClass());
    }

    public static EntityQueryKind classifyClass(final Class<?> queryBase) {
        if (queryBase == null) {
            return EntityQueryKind.UNKNOWN;
        }
        if (Player.class.isAssignableFrom(queryBase)) {
            return EntityQueryKind.PLAYERS;
        }
        if (ItemEntity.class.isAssignableFrom(queryBase)) {
            return EntityQueryKind.ITEMS;
        }
        if (LivingEntity.class.isAssignableFrom(queryBase)) {
            return EntityQueryKind.LIVING;
        }
        if (Entity.class.isAssignableFrom(queryBase) || queryBase == EntityAccess.class) {
            return EntityQueryKind.ANY;
        }
        return EntityQueryKind.UNKNOWN;
    }

    public static EntitySectionCounters.CountedEntityKind kindOf(final Object entity) {
        if (!(entity instanceof Entity e)) {
            return EntitySectionCounters.CountedEntityKind.UNKNOWN_CONSERVATIVE;
        }
        boolean player = e instanceof Player;
        boolean living = e instanceof LivingEntity;
        boolean item = e instanceof ItemEntity;
        boolean neverCollidable = e instanceof ExperienceOrb
                || e instanceof AreaEffectCloud
                || e instanceof Marker
                || e instanceof Display
                || e instanceof LightningBolt
                || e instanceof FishingHook;
        return EntitySectionCounters.CountedEntityKind.ofLabels(player, living, item, neverCollidable);
    }
}
