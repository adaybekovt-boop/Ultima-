package dev.ultima.sleeping.hopper;

import java.util.EnumSet;
import java.util.Set;

import dev.ultima.sleeping.SleepDecision;
import dev.ultima.sleeping.WakeChannel;

/**
 * Proof-table evaluator. Sleep is allowed only when every vanilla mutation that could
 * make the next {@code tryMoveItems} do work has an installed wake channel.
 *
 * <p>Agent decision (conservative): unlocked hoppers next to furnaces, brewing stands,
 * crafters, composters, loot chests, entity containers, or any non-allowlisted class
 * stay on vanilla polling. Locked hoppers may sleep regardless of neighbours because
 * vanilla does not read inventories while {@code ENABLED} is false.
 */
public final class HopperSleepPolicy {
    public static final Set<WakeChannel> PRODUCTION_CHANNELS = EnumSet.allOf(WakeChannel.class);

    private HopperSleepPolicy() {
    }

    public static SleepDecision evaluate(final HopperSleepSnapshot snapshot) {
        return evaluate(snapshot, PRODUCTION_CHANNELS);
    }

    public static SleepDecision evaluate(final HopperSleepSnapshot snapshot, final Set<WakeChannel> installed) {
        if (!snapshot.exactVanillaHopperClass()) {
            return SleepDecision.refuse(HopperWakeCondition.HOPPER_SUBCLASS.name());
        }
        if (snapshot.unresolvedOwnLoot()) {
            return SleepDecision.refuse(HopperWakeCondition.UNRESOLVED_LOOT_TABLE.name());
        }
        if (snapshot.cooldownTime() > 0) {
            return SleepDecision.refuse("ON_COOLDOWN");
        }

        EnumSet<WakeChannel> required = EnumSet.of(WakeChannel.BLOCK_UPDATE, WakeChannel.SELF_INVENTORY);
        if (!containsAll(installed, required)) {
            return SleepDecision.refuse("missing_self_or_block_wake");
        }

        if (!snapshot.enabled()) {
            return SleepDecision.allow("LOCKED", required);
        }

        if (snapshot.source().forbidsSleep()) {
            return SleepDecision.refuse(refuseReason(snapshot.source(), true));
        }
        if (snapshot.dest().forbidsSleep()) {
            return SleepDecision.refuse(refuseReason(snapshot.dest(), false));
        }

        if (!cannotPush(snapshot)) {
            return SleepDecision.refuse("CAN_PUSH");
        }
        if (!cannotPullFromContainer(snapshot)) {
            return SleepDecision.refuse("CAN_PULL");
        }

        addNeighborChannels(required, snapshot.source(), snapshot.dest());
        if (snapshot.dest().kind() == NeighborKind.ABSENT) {
            required.add(WakeChannel.CONTAINER_ENTITY);
        }
        if (snapshot.source().kind() == NeighborKind.ABSENT) {
            required.add(WakeChannel.CONTAINER_ENTITY);
            if (!snapshot.ownFull() && !snapshot.pickupBlockedByFullCube()) {
                if (snapshot.itemEntitiesInSuckAabb()) {
                    return SleepDecision.refuse("ITEMS_WAITING_IN_SUCK_AABB");
                }
                required.add(WakeChannel.ITEM_ENTITY);
            }
        }

        if (!containsAll(installed, required)) {
            return SleepDecision.refuse("missing_wake_channel:" + missing(installed, required));
        }
        return SleepDecision.allow("IDLE", required);
    }

    public static boolean cannotPush(final HopperSleepSnapshot snapshot) {
        return snapshot.ownEmpty()
                || snapshot.dest().kind() == NeighborKind.ABSENT
                || snapshot.dest().occupancy() == Occupancy.FULL;
    }

    public static boolean cannotPullFromContainer(final HopperSleepSnapshot snapshot) {
        return snapshot.ownFull()
                || snapshot.source().kind() == NeighborKind.ABSENT
                || snapshot.source().occupancy() == Occupancy.EMPTY;
    }

    /**
     * Naive implementations that only hook {@code HopperBlock.entityInside} miss item entities
     * sitting in the block above the hopper. The policy therefore requires {@link WakeChannel#ITEM_ENTITY}
     * whenever pickup is possible, and refuses sleep if that channel is not installed.
     */
    public static SleepDecision evaluateWithoutItemEntityWake(final HopperSleepSnapshot snapshot) {
        EnumSet<WakeChannel> naive = EnumSet.allOf(WakeChannel.class);
        naive.remove(WakeChannel.ITEM_ENTITY);
        return evaluate(snapshot, naive);
    }

    private static void addNeighborChannels(
            final EnumSet<WakeChannel> required, final NeighborState source, final NeighborState dest) {
        if (source.kind() == NeighborKind.SLEEP_SAFE || dest.kind() == NeighborKind.SLEEP_SAFE) {
            required.add(WakeChannel.NEIGHBOR_INVENTORY);
        }
        required.add(WakeChannel.BLOCK_UPDATE);
    }

    private static String refuseReason(final NeighborState state, final boolean source) {
        String side = source ? "SOURCE" : "DEST";
        if (state.unresolvedLoot()) {
            return side + "_" + HopperWakeCondition.UNRESOLVED_LOOT_TABLE.name();
        }
        return side + "_" + HopperWakeCondition.NON_ALLOWLISTED_CONTAINER.name();
    }

    private static boolean containsAll(final Set<WakeChannel> installed, final Set<WakeChannel> required) {
        return installed.containsAll(required);
    }

    private static String missing(final Set<WakeChannel> installed, final Set<WakeChannel> required) {
        EnumSet<WakeChannel> missing = EnumSet.copyOf(required);
        missing.removeAll(installed);
        return missing.toString();
    }
}
