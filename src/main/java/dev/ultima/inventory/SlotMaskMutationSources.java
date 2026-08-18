package dev.ultima.inventory;

import java.util.List;

/**
 * Complete vanilla 26.2 inventory mutation inventory used to design the slot mask.
 *
 * <p>Every row is a path that can change whether a slot is empty. The mask is updated on list
 * writes ({@code NonNullList.set}/{@code clear}) and on the Container methods listed as
 * {@link Coverage#TRACKED}. In-place {@code ItemStack} count changes that never rewrite the list
 * are {@link Coverage#CONSERVATIVE}: they can only leave extra empty bits, which verification
 * heals. Unknown / modded containers have {@link Coverage#FALLBACK} (no mask, vanilla scan).
 *
 * <p>Hopper insert/extract <em>loops</em> are intentionally not rewritten here (hopper sleeping
 * is a separate module). Hopper {@code setItem} still updates the mask of the container it mutates.
 */
public final class SlotMaskMutationSources {
    private SlotMaskMutationSources() {
    }

    public enum Coverage {
        TRACKED,
        CONSERVATIVE,
        FALLBACK
    }

    public record Source(String vanillaPath, String effect, Coverage coverage, String hook) {
    }

    public static final List<Source> VANILLA_26_2 = List.of(
            new Source(
                    "Container.setItem / BaseContainerBlockEntity.setItem / SimpleContainer.setItem",
                    "Replaces the stack in a slot; may fill or empty it",
                    Coverage.TRACKED,
                    "NonNullList.set occupancy + Container mixins"),
            new Source(
                    "Container.removeItem / ContainerHelper.removeItem",
                    "ItemStack.split; slot may become empty without a list rewrite",
                    Coverage.CONSERVATIVE,
                    "split-to-empty leaves an extra 1; next verify heals"),
            new Source(
                    "Container.removeItemNoUpdate / ContainerHelper.takeItem",
                    "NonNullList.set(slot, EMPTY)",
                    Coverage.TRACKED,
                    "NonNullList.set"),
            new Source(
                    "Container.clearContent / NonNullList.clear",
                    "Every slot written to the list default (EMPTY)",
                    Coverage.TRACKED,
                    "NonNullList.clear → set(i, EMPTY)"),
            new Source(
                    "SimpleContainer.addItem / moveItemToEmptySlots",
                    "Fills the first empty slot via setItem",
                    Coverage.TRACKED,
                    "setItem → NonNullList.set"),
            new Source(
                    "SimpleContainer.moveItemsBetweenStacks",
                    "grow/shrink on an already occupied stack",
                    Coverage.CONSERVATIVE,
                    "occupancy unchanged unless shrink hits 0"),
            new Source(
                    "SimpleContainer.removeItemType",
                    "split from matching slots, high index to low",
                    Coverage.CONSERVATIVE,
                    "in-place empty; extra 1s"),
            new Source(
                    "SimpleContainer.fromItemList / removeAllItems",
                    "clear then addItem, or clear after copy",
                    Coverage.TRACKED,
                    "clear + setItem"),
            new Source(
                    "AbstractFurnaceBlockEntity.setItem",
                    "Writes items.set directly, bypassing BaseContainerBlockEntity.setItem",
                    Coverage.TRACKED,
                    "NonNullList.set on the furnace list"),
            new Source(
                    "HopperBlockEntity.setItem / removeItem",
                    "Direct list writes after unpackLootTable; hopper transfer loops unchanged",
                    Coverage.TRACKED,
                    "NonNullList.set only — no HopperBlockEntity extract/insert rewrite"),
            new Source(
                    "CrafterBlockEntity.setItem",
                    "May re-enable a disabled slot then super.setItem",
                    Coverage.TRACKED,
                    "super.setItem → BaseContainerBlockEntity list write"),
            new Source(
                    "ListBackedContainer.setItem / setItemNoUpdate",
                    "Chiseled bookshelf, campfire-adjacent list containers",
                    Coverage.TRACKED,
                    "getItems().set"),
            new Source(
                    "Inventory.setItem / add / addResource / pickSlot / addAndPickItem",
                    "Main 36 slots via items.set; equipment slots 36+ via EntityEquipment",
                    Coverage.TRACKED,
                    "NonNullList.set (0–35) + Inventory mixin (equipment)"),
            new Source(
                    "Inventory.removeItem / removeItem(ItemStack) / dropAll / load / replaceWith / clearContent",
                    "List rewrites and equipment clears",
                    Coverage.TRACKED,
                    "list set/clear + Inventory mixin rebuild on equipment/dropAll"),
            new Source(
                    "ContainerHelper.loadAllItems",
                    "World load / loot remainder writes items.set per slot",
                    Coverage.TRACKED,
                    "NonNullList.set"),
            new Source(
                    "ContainerHelper.clearOrCountMatchingItems",
                    "shrink then setItem(EMPTY) if the stack became empty",
                    Coverage.TRACKED,
                    "setItem EMPTY when count hits 0; shrink-only is conservative"),
            new Source(
                    "ItemContainerContents.copyInto",
                    "Component apply writes every destination slot",
                    Coverage.TRACKED,
                    "NonNullList.set from BaseContainerBlockEntity.applyImplicitComponents"),
            new Source(
                    "RandomizableContainer.unpackLootTable / LootTable.fill",
                    "Chest/barrel/minecart loot generation via setItem",
                    Coverage.TRACKED,
                    "setItem after loot table is cleared"),
            new Source(
                    "ContainerEntity.setChestVehicleItem / removeChestVehicleItem*",
                    "Chest boat / chest minecart list writes",
                    Coverage.TRACKED,
                    "getItemStacks().set + constructor attach"),
            new Source(
                    "CompoundContainer.setItem/removeItem/clearContent",
                    "Delegates to the two halves; double chest is 27+27=54",
                    Coverage.TRACKED,
                    "Child containers hold the masks; CompoundContainer.isEmpty delegates"),
            new Source(
                    "AbstractContainerMenu / Slot.set / player click handlers",
                    "Opened-container clicks mutate via Container.setItem",
                    Coverage.TRACKED,
                    "setItem"),
            new Source(
                    "HopperBlockEntity.addItem / tryMoveItems / DispenserBlock / DropperBlock",
                    "Cross-container insert/extract calling setItem/removeItem on the target",
                    Coverage.TRACKED,
                    "Target container mask only; this module does not rewrite hopper loops"),
            new Source(
                    "ItemStack.shrink / grow / setCount / split on a live slot stack",
                    "Count → 0 without NonNullList.set",
                    Coverage.CONSERVATIVE,
                    "Extra 1 bits; first-use and periodic verifyAgainst"),
            new Source(
                    "MerchantContainer / ResultContainer / TransientCraftingContainer.setItem",
                    "Menu-side 1–9 slot lists",
                    Coverage.TRACKED,
                    "Constructor attach + NonNullList.set"),
            new Source(
                    "ContainerSingleItem.setTheItem (jukebox, pot, lectern book)",
                    "One-slot containers; scan cost is already O(1)",
                    Coverage.FALLBACK,
                    "No mask; vanilla path"),
            new Source(
                    "Modded Container that does not use NonNullList / our Mixins",
                    "Unknown occupancy writes",
                    Coverage.FALLBACK,
                    "No SlotMaskHolder → vanilla isEmpty/comparator loops"));

    public static int trackedCount() {
        int n = 0;
        for (Source source : VANILLA_26_2) {
            if (source.coverage() == Coverage.TRACKED) {
                n++;
            }
        }
        return n;
    }
}
