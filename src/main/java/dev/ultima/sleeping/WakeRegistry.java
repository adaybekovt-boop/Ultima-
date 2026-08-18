package dev.ultima.sleeping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Per-level spatial subscriptions: a sleeping controller watches a small set of block
 * positions (self, source cell, destination cell) and is woken by any covering channel.
 */
public final class WakeRegistry {
    private final IdentityHashMap<Level, Map<Long, List<SleepController>>> watchers = new IdentityHashMap<>();
    private final IdentityHashMap<SleepController, Registration> registrations = new IdentityHashMap<>();

    public synchronized void register(final Level level, final SleepController controller, final long[] packedPositions) {
        this.unregister(controller);
        Map<Long, List<SleepController>> byPos = this.watchers.computeIfAbsent(level, unused -> new HashMap<>());
        long[] copy = packedPositions.clone();
        for (long packed : copy) {
            byPos.computeIfAbsent(packed, unused -> new ArrayList<>(2)).add(controller);
        }
        this.registrations.put(controller, new Registration(level, copy));
    }

    public synchronized void unregister(final SleepController controller) {
        Registration registration = this.registrations.remove(controller);
        if (registration == null) {
            return;
        }
        Map<Long, List<SleepController>> byPos = this.watchers.get(registration.level);
        if (byPos == null) {
            return;
        }
        for (long packed : registration.positions) {
            List<SleepController> list = byPos.get(packed);
            if (list == null) {
                continue;
            }
            list.remove(controller);
            if (list.isEmpty()) {
                byPos.remove(packed);
            }
        }
        if (byPos.isEmpty()) {
            this.watchers.remove(registration.level);
        }
    }

    public synchronized void wakeAt(final Level level, final BlockPos pos, final WakeChannel channel) {
        this.wakePacked(level, pos.asLong(), channel);
    }

    public synchronized void wakePacked(final Level level, final long packed, final WakeChannel channel) {
        Map<Long, List<SleepController>> byPos = this.watchers.get(level);
        if (byPos == null) {
            return;
        }
        List<SleepController> list = byPos.get(packed);
        if (list == null || list.isEmpty()) {
            return;
        }
        long gameTime = level.getGameTime();
        List<SleepController> snapshot = new ArrayList<>(list);
        for (SleepController controller : snapshot) {
            try {
                controller.wake(channel, gameTime);
            } catch (Throwable error) {
                HopperSleepFailOpen.failOpen("wake@" + packed, controller, error);
            }
        }
    }

    public synchronized boolean hasWatchers() {
        return !this.registrations.isEmpty();
    }

    public synchronized int watcherCount() {
        return this.registrations.size();
    }

    public synchronized void clear() {
        this.watchers.clear();
        this.registrations.clear();
    }

    /**
     * Drop every subscription for an unloaded level. Controllers are detached so they
     * cannot keep a stale {@link Level} reference after {@code SERVER_STOPPED} / world unload.
     */
    public synchronized void clearLevel(final Level level) {
        if (level == null) {
            return;
        }
        List<SleepController> affected = new ArrayList<>();
        for (var entry : List.copyOf(this.registrations.entrySet())) {
            if (entry.getValue().level() == level) {
                affected.add(entry.getKey());
            }
        }
        for (SleepController controller : affected) {
            controller.detach();
        }
        this.watchers.remove(level);
    }

    private record Registration(Level level, long[] positions) {
    }
}
