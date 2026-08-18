package dev.ultima.sleeping.hopper;

import dev.ultima.sleeping.WakeChannel;
import org.jspecify.annotations.Nullable;

/**
 * Every vanilla mutation that can change the next hopper tick's result. The table is the
 * proof contract: sleep is allowed only when each relevant row has an installed wake
 * channel. Uncovered rows refuse sleep (vanilla polling) for that instance.
 */
public enum HopperWakeCondition {
    COOLDOWN_EXPIRED(
            "Transfer cooldown reaches 0",
            "pushItemsTick decrements cooldownTime every tick",
            WakeChannel.TICK_BOOKKEEPING),
    ENABLED_UNLOCKED(
            "Hopper ENABLED becomes true (redstone lock released)",
            "HopperBlock.checkPoweredState -> Level.setBlock on the hopper",
            WakeChannel.BLOCK_UPDATE),
    FACING_CHANGED(
            "Hopper FACING changes, so dest cell changes",
            "Level.setBlock / BlockEntity.setBlockState on the hopper",
            WakeChannel.BLOCK_UPDATE),
    OWN_INVENTORY_SET_ITEM(
            "Items inserted into this hopper",
            "HopperBlockEntity.setItem (player menu, hopper insert, commands)",
            WakeChannel.SELF_INVENTORY),
    OWN_INVENTORY_REMOVE_ITEM(
            "Items removed from this hopper",
            "HopperBlockEntity.removeItem / removeItemNoUpdate / clearContent",
            WakeChannel.SELF_INVENTORY),
    OWN_INVENTORY_MERGE_GROW(
            "Existing stack in this hopper grew without setItem",
            "HopperBlockEntity.tryMoveInItem grow + setChanged",
            WakeChannel.SELF_INVENTORY),
    SOURCE_INVENTORY_MUTATED(
            "Source container gained/lost items this hopper could pull",
            "Allowlisted BaseContainerBlockEntity setItem/removeItem/setChanged",
            WakeChannel.NEIGHBOR_INVENTORY),
    DEST_INVENTORY_MUTATED(
            "Destination container gained space or emptied",
            "Allowlisted BaseContainerBlockEntity setItem/removeItem/setChanged",
            WakeChannel.NEIGHBOR_INVENTORY),
    SOURCE_BLOCK_CHANGED(
            "Block above became or ceased to be a container, or pickup-blocking cube changed",
            "Level.setBlock at pos.above()",
            WakeChannel.BLOCK_UPDATE),
    DEST_BLOCK_CHANGED(
            "Block in facing direction became or ceased to be a container",
            "Level.setBlock at pos.relative(facing)",
            WakeChannel.BLOCK_UPDATE),
    ITEM_ENTITY_IN_SUCK_AABB(
            "ItemEntity entered the suck AABB (hopper bowl + block above)",
            "Entity.setPosRaw / addEntity; entityInside is not sufficient for the block above",
            WakeChannel.ITEM_ENTITY),
    CONTAINER_ENTITY_AT_SOURCE(
            "Container entity (chest/hopper minecart, chest boat) entered the source cell",
            "Entity.setPosRaw / PersistentEntitySectionManager.addEntity",
            WakeChannel.CONTAINER_ENTITY),
    CONTAINER_ENTITY_AT_DEST(
            "Container entity entered the destination cell",
            "Entity.setPosRaw / PersistentEntitySectionManager.addEntity",
            WakeChannel.CONTAINER_ENTITY),
    UNRESOLVED_LOOT_TABLE(
            "Randomizable container still has a loot table; getItem would unpack items",
            "Loot unpack on first access — not a tracked mutation until unpacked",
            null),
    INTERNAL_TICK_MUTATION(
            "Neighbour furnace/brewing stand/crafter mutates NonNullList during its own tick",
            "items.set / shrink without Hopper-tracked Container methods",
            null),
    WORLDLY_CONTAINER_HOLDER(
            "Composter (or any WorldlyContainerHolder) inventory is a transient wrapper",
            "ComposterBlock.getContainer; level property changes are not Container.setItem",
            null),
    NON_ALLOWLISTED_CONTAINER(
            "Modded or non-allowlisted vanilla Container (jukebox, pot, lectern, shelf, …)",
            "Unknown mutation surface by definition",
            null),
    HOPPER_SUBCLASS(
            "HopperBlockEntity subclass from another mod",
            "Unknown extra tick/mutation behaviour",
            null);

    private final String vanillaEffect;
    private final String mutationSource;
    private final @Nullable WakeChannel channel;

    HopperWakeCondition(final String vanillaEffect, final String mutationSource, final @Nullable WakeChannel channel) {
        this.vanillaEffect = vanillaEffect;
        this.mutationSource = mutationSource;
        this.channel = channel;
    }

    public String vanillaEffect() {
        return this.vanillaEffect;
    }

    public String mutationSource() {
        return this.mutationSource;
    }

    public @Nullable WakeChannel channel() {
        return this.channel;
    }

    public boolean sleepForbiddenWhenRelevant() {
        return this.channel == null;
    }
}
