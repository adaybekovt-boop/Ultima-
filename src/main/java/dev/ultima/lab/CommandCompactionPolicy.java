package dev.ultima.lab;

import java.util.Properties;

/**
 * Compaction trigger for retained indirect commands. Thresholds are explicit so
 * later hardware tests can vary them; nothing is hard-coded as a hidden magic
 * cutoff in the submit inner loop.
 */
public final class CommandCompactionPolicy {
    public static final double DEFAULT_DEAD_RATIO = 0.50;
    public static final int DEFAULT_MIN_DEAD = 64;
    public static final String DEAD_RATIO_PROPERTY = "command_compaction.dead_ratio";
    public static final String MIN_DEAD_PROPERTY = "command_compaction.min_dead";

    private final double deadRatioThreshold;
    private final int minDead;

    public CommandCompactionPolicy() {
        this(DEFAULT_DEAD_RATIO, DEFAULT_MIN_DEAD);
    }

    public CommandCompactionPolicy(final double deadRatioThreshold, final int minDead) {
        if (!(deadRatioThreshold >= 0.0) || deadRatioThreshold > 1.0 || Double.isNaN(deadRatioThreshold)) {
            throw new IllegalArgumentException("deadRatioThreshold must be in [0, 1], got " + deadRatioThreshold);
        }
        if (minDead < 0) {
            throw new IllegalArgumentException("minDead must be >= 0, got " + minDead);
        }
        this.deadRatioThreshold = deadRatioThreshold;
        this.minDead = minDead;
    }

    public static CommandCompactionPolicy fromProperties(final Properties properties) {
        double ratio = DEFAULT_DEAD_RATIO;
        int minDead = DEFAULT_MIN_DEAD;
        String ratioText = properties.getProperty(DEAD_RATIO_PROPERTY);
        if (ratioText != null) {
            ratio = Double.parseDouble(ratioText.trim());
        }
        String minDeadText = properties.getProperty(MIN_DEAD_PROPERTY);
        if (minDeadText != null) {
            minDead = Integer.parseInt(minDeadText.trim());
        }
        return new CommandCompactionPolicy(ratio, minDead);
    }

    public double deadRatioThreshold() {
        return this.deadRatioThreshold;
    }

    public int minDead() {
        return this.minDead;
    }

    public boolean shouldCompact(final int totalCommands, final int liveCommands) {
        if (totalCommands < 0 || liveCommands < 0 || liveCommands > totalCommands) {
            throw new IllegalArgumentException(
                    "invalid command counts total=" + totalCommands + " live=" + liveCommands);
        }
        int dead = totalCommands - liveCommands;
        if (dead < this.minDead || totalCommands == 0) {
            return false;
        }
        return ((double)dead / (double)totalCommands) >= this.deadRatioThreshold;
    }

    public static double deadRatio(final int totalCommands, final int liveCommands) {
        if (totalCommands <= 0) {
            return 0.0;
        }
        return (double)(totalCommands - liveCommands) / (double)totalCommands;
    }
}
