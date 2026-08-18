package dev.ultima.sleeping.hopper;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Formal wake table for the hopper prototype. This is the proof artefact: every row is
 * either covered by a synchronous {@link dev.ultima.sleeping.WakeChannel} or explicitly
 * forbids sleep for that instance (vanilla polling).
 */
public final class HopperWakeTable {
    private HopperWakeTable() {
    }

    public static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (HopperWakeCondition condition : HopperWakeCondition.values()) {
            rows.add(new Row(
                    condition,
                    condition.vanillaEffect(),
                    condition.mutationSource(),
                    condition.channel() == null ? "NO — sleep forbidden" : "Yes — " + condition.channel().name(),
                    condition.sleepForbiddenWhenRelevant()
                            ? "Sleep forbidden for this instance; vanilla polling"
                            : "Wake synchronously on this channel"));
        }
        return List.copyOf(rows);
    }

    public record Row(
            HopperWakeCondition condition,
            String vanillaEffect,
            String mutationSource,
            String wakeCoverage,
            String ifUncovered) {
        public @Nullable String channelName() {
            return this.condition.channel() == null ? null : this.condition.channel().name();
        }
    }
}
