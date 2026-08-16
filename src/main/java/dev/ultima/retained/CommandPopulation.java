package dev.ultima.retained;

/**
 * Per-frame retained command accounting. Compaction is not applied; these
 * numbers exist so a long chunk-flight run can show whether total records grow
 * while the live visible set stays bounded.
 */
public record CommandPopulation(
        int submitGroupCount,
        int totalCommandRecords,
        int liveCommandRecords,
        int hiddenZeroInstanceCommands,
        double liveToTotalRatio,
        int largestSubmitGroupCommands,
        int commandArrayCapacity,
        long commandBufferCapacityBytes,
        int commandBufferReallocs,
        int commandArrayReallocs,
        int commandsAdded,
        int commandsRemoved,
        int visibilityToggles,
        int maxTotalCommandRecords,
        int maxHiddenCommands,
        double minLiveRatio) {
    public static CommandPopulation empty() {
        return new CommandPopulation(0, 0, 0, 0, 1.0, 0, 0, 0L, 0, 0, 0, 0, 0, 0, 0, 1.0);
    }

    public static double ratio(final int live, final int total) {
        return total == 0 ? 1.0 : (double)live / (double)total;
    }
}
