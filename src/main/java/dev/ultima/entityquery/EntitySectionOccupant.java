package dev.ultima.entityquery;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityAccess;

/**
 * Classifies a section occupant for the four occupancy counters. Unknown {@code EntityAccess}
 * implementations (modded storage that is not an {@code Entity}) mark the section unknown so an
 * early-out is never taken.
 */
public final class EntitySectionOccupant {
    public final boolean player;
    public final boolean living;
    public final boolean item;
    public final boolean unknown;

    private EntitySectionOccupant(final boolean player, final boolean living, final boolean item, final boolean unknown) {
        this.player = player;
        this.living = living;
        this.item = item;
        this.unknown = unknown;
    }

    public static EntitySectionOccupant of(final Object entity) {
        if (!(entity instanceof EntityAccess)) {
            return new EntitySectionOccupant(false, false, false, true);
        }
        if (!(entity instanceof Entity)) {
            return new EntitySectionOccupant(false, false, false, true);
        }
        boolean player = entity instanceof Player;
        boolean living = entity instanceof LivingEntity;
        boolean item = entity instanceof ItemEntity;
        return new EntitySectionOccupant(player, living, item, false);
    }

    public void addTo(final EntitySectionCounters counters) {
        counters.add(this.player, this.living, this.item, this.unknown);
    }

    public void removeFrom(final EntitySectionCounters counters) {
        counters.remove(this.player, this.living, this.item, this.unknown);
    }
}
