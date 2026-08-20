package dev.ultima.inventory;

/**
 * Complete inventory mutation catalog for Minecraft 26.2, compiled from
 * {@code .agent/vanilla-src}. Every row is a path that can change whether a slot is empty.
 *
 * <p>The mask is updated on the indexed {@code Container} methods and invalidated on
 * {@code setChanged} that is not nested in those methods (in-place {@code ItemStack} grow/shrink).
 * Unlisted or modded paths fail open: the hint is marked untrusted and the next use rebuilds from
 * {@code getItem}.
 *
 * <h2>Proven 26.2 coverage (independent audit, round 3)</h2>
 *
 * <p>{@code BaseContainerBlockEntity.applyImplicitComponents} is <em>not</em> covered by
 * {@code setItems}. Vanilla copies with
 * {@code ItemContainerContents.copyInto(this.getItems())} which does {@code destination.set(i, …)}
 * on the existing {@code NonNullList}. {@code ChiseledBookShelfBlockEntity} and
 * {@code ShelfBlockEntity} override the same way onto {@code this.items}. The public entry is
 * {@code BlockEntity.applyComponentsFromItemStack} → {@code applyComponents} (public final) →
 * {@code applyImplicitComponents}. {@code BlockEntityMixin.ultimaApplyComponents} wraps that
 * final method with {@link SlotMaskHooks#runInvalidateIfContainer}.
 *
 * <p>{@code loadWithComponents} / {@code loadCustomOnly} call {@code loadAdditional}. Chests,
 * hoppers, furnaces, brewing stands, crafters, shulkers, shelves, and chiseled bookshelves all
 * write {@code ContainerHelper.loadAllItems} into the list (often after replacing the list
 * identity) without {@code setItems}. Those wraps invalidate on every container block entity
 * reload, including {@code /data} and structure paste onto a live entity.
 *
 * <p>{@code ListBackedContainer.setItemNoUpdate} is used by {@code ShelfBlockEntity.swapItemNoUpdate}
 * without {@code setChanged}. It is wrapped. {@code setItem} calls {@code setItemNoUpdate} then
 * {@code setChanged}; nested indexed-write depth is intentional.
 *
 * <p>{@code LootTable.fill} uses {@code container.setItem}. Indexed wraps fire per slot; the
 * {@code unpackLootTable} / {@code unpackChestVehicleLootTable} wraps still invalidate when a
 * loot table was present so a mid-fill throw cannot leave a trusted-empty mask. No-op unpack
 * (table already {@code null}) does not invalidate — otherwise every chest {@code getItem}
 * would untrust the mask.
 *
 * <p>Furnace {@code consumeFuel}/{@code burn} and brewing {@code doBrew}/fuel shrink write the
 * list then call static {@code BlockEntity.setChanged(Level, BlockPos, BlockState)}, which is
 * wrapped. Hopper merge-grow uses {@code ItemStack.grow} then {@code container.setChanged()}
 * (occupancy-noop while the slot stays occupied). Hopper {@code setItem}/{@code removeItem}
 * skip {@code setChanged} and have their own wraps.
 *
 * <p>Not consumed (allowlist fail-open, no skip-filter): {@code MerchantContainer},
 * {@code ResultContainer}, {@code TransientCraftingContainer}, lectern anonymous book
 * {@code Container}, {@code ComposterBlock} inner containers, {@code JukeboxBlockEntity},
 * {@code DecoratedPotBlockEntity}, {@code CampfireBlockEntity} ({@code Clearable}, not
 * {@code Container}). Horse/villager/allay/piglin inventories are {@code SimpleContainer}
 * instances and use the {@code SimpleContainer} wraps.
 */
public final class VanillaInventoryMutationSources {
    /**
     * Ordered catalog used by tests so the documented list cannot drift from the intended coverage.
     */
    public static final String[] SOURCES = {
            "Container.setItem — direct slot replace (BaseContainerBlockEntity, SimpleContainer, Inventory, ContainerEntity, ListBackedContainer) — hooked",
            "Container.removeItem — split/take reducing count, possibly to empty (ContainerHelper.removeItem → ItemStack.split) — hooked",
            "Container.removeItemNoUpdate — takeItem / list.set(EMPTY) without comparators — hooked on BaseContainer, SimpleContainer, Inventory, ListBackedContainer, ContainerEntity",
            "ListBackedContainer.setItemNoUpdate — ShelfBlockEntity.swapItemNoUpdate writes without setChanged — hooked",
            "Container.clearContent — NonNullList.clear fills EMPTY; CompoundContainer clears both halves — hooked",
            "SimpleContainer.addItem — stacks into occupied slots (grow/shrink) then empty slots via setItem — hooked (invalidate)",
            "SimpleContainer.removeItemType — reverse-index split; setChanged without per-slot setItem — hooked (invalidate)",
            "SimpleContainer.fromItemList — clearContent + addItem (both hooked); getItems().set is a public-field mod path and fails open",
            "SimpleContainer.removeAllItems — clearContent (hooked)",
            "ItemStack.setCount / grow / shrink / split / copyAndClear while the stack lives in a slot (hopper merge, furnace burn, brewing, player addResource)",
            "Container.setChanged after an in-place count change (HopperBlockEntity.tryMoveInItem, SimpleContainer.moveItemsBetweenStacks) — hooked",
            "BlockEntity.setChanged()V and setChanged(Level, BlockPos, BlockState) — WrapMethod + finally; furnace/brewing static calls — hooked",
            "HopperBlockEntity.setItem / removeItem — overrides that skip BaseContainerBlockEntity.setChanged — hooked",
            "AbstractFurnaceBlockEntity.setItem — overrides that write items.set without super — hooked",
            "AbstractFurnaceBlockEntity.consumeFuel / burn — items.set + shrink then static setChanged — covered by BlockEntityMixin static wrap",
            "BrewingStandBlockEntity.doBrew / fuel shrink — list.set and ItemStack.shrink then static setChanged — covered by BlockEntityMixin static wrap",
            "CrafterBlockEntity.setItem — super.setItem after enabling a disabled slot — covered by BaseContainerBlockEntityMixin.setItem",
            "ChiseledBookShelfBlockEntity.setItem / removeItem — list.set, bypasses ListBackedContainer defaults — hooked; removeItemNoUpdate uses the ListBacked default — hooked",
            "Slot.set / setByPlayer / safeInsert / safeTake — player-container clicks (AbstractContainerMenu.clicked → Slot → Container.setItem)",
            "AbstractContainerMenu.setItem — client/server slot sync packet → Slot.set → Container.setItem",
            "HopperBlockEntity.addItem / tryMoveInItem / ejectItems / tryTakeInItemFromSlot — hopper insert/extract including merge-grow + setChanged",
            "DispenserBlock / DropperBlock dispense — removeItem from the dispenser container — hooked via RandomizableContainerBlockEntity/BaseContainer",
            "ContainerHelper.loadAllItems / takeItem / removeItem / clearOrCountMatchingItems — NBT load via BlockEntity load wraps; /clear uses setItem when a stack empties",
            "RandomizableContainer.unpackLootTable — LootTable.fill uses setItem; wrap invalidates when a table was present, including mid-fill throw — hooked",
            "ContainerEntity.unpackChestVehicleLootTable — vehicles are not RandomizableContainer; same fill + gated invalidate — hooked",
            "BlockEntity.applyComponents → applyImplicitComponents (DataComponents.CONTAINER copyInto, not setItems) — hooked",
            "BlockEntity.loadWithComponents / loadCustomOnly → loadAdditional loadAllItems without setItems — hooked",
            "BaseContainerBlockEntity.setItems — replaces the backing NonNullList (ChestBlockEntity.swapContents) — hooked",
            "Inventory.add / addResource / removeItem(ItemStack) / dropAll / replaceWith / load — player inventory bulk, respawn/dimension copy, and identity remove — hooked",
            "Inventory.setSelectedItem / addAndPickItem / pickSlot — items.set without Container.setItem — hooked",
            "ContainerEntity.setChestVehicleItem / removeChestVehicleItem / clearChestVehicleContent / readChestVehicleSaveData — hooked",
            "CompoundContainer.setItem/removeItem — delegated to container1/container2; mask lives on the halves — no extra wrap needed",
            "Allowlist fail-open (not consumed): MerchantContainer, ResultContainer, TransientCraftingContainer, lectern book Container, ComposterBlock inners, JukeboxBlockEntity, DecoratedPotBlockEntity, CampfireBlockEntity"
    };

    private VanillaInventoryMutationSources() {
    }

    public static int sourceCount() {
        return SOURCES.length;
    }
}
