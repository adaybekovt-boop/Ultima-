package dev.ultima.sleeping;

import java.util.EnumSet;
import java.util.Set;

/**
 * Result of a proof-table evaluation: sleep is allowed only when every relevant vanilla
 * mutation is covered by an installed {@link WakeChannel}.
 */
public record SleepDecision(boolean allowed, String reason, Set<WakeChannel> requiredChannels) {
    public SleepDecision {
        requiredChannels = requiredChannels.isEmpty()
                ? EnumSet.noneOf(WakeChannel.class)
                : EnumSet.copyOf(requiredChannels);
    }

    public static SleepDecision allow(final String reason, final Set<WakeChannel> requiredChannels) {
        return new SleepDecision(true, reason, requiredChannels);
    }

    public static SleepDecision refuse(final String reason) {
        return new SleepDecision(false, reason, EnumSet.noneOf(WakeChannel.class));
    }
}
