package dev.ultima.inventory;

/**
 * Complete inventory mutation catalog for Minecraft 26.2, compiled from
 * {@code .agent/vanilla-src}. Every row is a path that can change whether a slot is empty.
 *
 * <p>The mask is updated on the indexed {@code Container} methods and invalidated on
 * {@code setChanged} that is not nested in those methods (in-place {@code ItemStack} grow/shrink).
 * Unlisted or modded paths fail open: the hint is marked untrusted and the next use rebuilds from
 * {@code getItem}.
 */
public final class VanillaInventoryMutationSources {
    /**
     * Ordered catalog used by tests so the documented list cannot drift from the intended coverage.
     */
    public static final String[] SOURCES = {
            "Container.setItem — direct slot replace (BaseContainerBlockEntity, SimpleContainer, Inventory, ContainerEntity, ListBackedContainer)",
            "Container.removeItem — split/take reducing count, possibly to empty (ContainerHelper.removeItem → ItemStack.split)",
            "Container.removeItemNoUpdate — takeItem / list.set(EMPTY) without comparators",
            "Container.clearContent — NonNullList.clear fills EMPTY; CompoundContainer clears both halves",
            "SimpleContainer.addItem — stacks into occupied slots (grow/shrink) then empty slots via setItem",
            "SimpleContainer.removeItemType — reverse-index split; setChanged without per-slot setItem",
            "SimpleContainer.fromItemList / getItems().set — bulk replace",
            "ItemStack.setCount / grow / shrink / split / copyAndClear while the stack lives in a slot (hopper merge, furnace burn, brewing, player addResource)",
            "Container.setChanged after an in-place count change (HopperBlockEntity.tryMoveInItem, SimpleContainer.moveItemsBetweenStacks)",
            "HopperBlockEntity.setItem / removeItem — overrides that skip BaseContainerBlockEntity.setChanged",
            "AbstractFurnaceBlockEntity.setItem — overrides that write items.set without super",
            "AbstractFurnaceBlockEntity.consumeFuel / burn — items.set + shrink without Container.setItem",
            "BrewingStandBlockEntity.doBrew / fuel shrink — list.set and ItemStack.shrink",
            "CrafterBlockEntity.setItem — super.setItem after enabling a disabled slot",
            "ChiseledBookShelfBlockEntity.setItem / removeItem — list.set, bypasses ListBackedContainer defaults",
            "Slot.set / setByPlayer / safeInsert / safeTake — player-container clicks (AbstractContainerMenu.clicked → Slot → Container.setItem)",
            "AbstractContainerMenu.setItem — client/server slot sync packet",
            "HopperBlockEntity.addItem / tryMoveInItem / ejectItems / tryTakeInItemFromSlot — hopper insert/extract including merge-grow",
            "DispenserBlock / DropperBlock dispense — removeItem from the dispenser container",
            "ContainerHelper.loadAllItems / takeItem / removeItem / clearOrCountMatchingItems — NBT load and /clear",
            "RandomizableContainer.unpackLootTable / lootTable.fill — generates items into previously empty slots",
            "BaseContainerBlockEntity.applyImplicitComponents (DataComponents.CONTAINER) — item-form chests",
            "BaseContainerBlockEntity.setItems — replaces the backing NonNullList (loadAdditional)",
            "Inventory.add / addResource / removeItem(ItemStack) / dropAll / load — player inventory bulk and identity remove",
            "ContainerEntity.setChestVehicleItem / removeChestVehicleItem / clearChestVehicleContent / readChestVehicleSaveData",
            "CompoundContainer.setItem/removeItem — delegated to container1/container2; mask lives on the halves"
    };

    private VanillaInventoryMutationSources() {
    }

    public static int sourceCount() {
        return SOURCES.length;
    }
}
