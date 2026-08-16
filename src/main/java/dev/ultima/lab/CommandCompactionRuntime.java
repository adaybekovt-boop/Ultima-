package dev.ultima.lab;

/**
 * Runtime policy for {@code command_compaction}. Thresholds come from system
 * properties so a later hardware pass can vary them without a rebuild:
 *
 * <pre>
 * -Dultima.command_compaction.dead_ratio=0.5
 * -Dultima.command_compaction.min_dead=64
 * </pre>
 */
public final class CommandCompactionRuntime {
    private static final String DEAD_RATIO = "ultima.command_compaction.dead_ratio";
    private static final String MIN_DEAD = "ultima.command_compaction.min_dead";
    private static volatile CommandCompactionPolicy policy = load();

    private CommandCompactionRuntime() {
    }

    public static CommandCompactionPolicy policy() {
        return policy;
    }

    public static void setPolicy(final CommandCompactionPolicy next) {
        policy = next;
    }

    public static void reloadFromSystemProperties() {
        policy = load();
    }

    private static CommandCompactionPolicy load() {
        double ratio = CommandCompactionPolicy.DEFAULT_DEAD_RATIO;
        int minDead = CommandCompactionPolicy.DEFAULT_MIN_DEAD;
        String ratioText = System.getProperty(DEAD_RATIO);
        if (ratioText != null) {
            ratio = Double.parseDouble(ratioText);
        }
        String minDeadText = System.getProperty(MIN_DEAD);
        if (minDeadText != null) {
            minDead = Integer.parseInt(minDeadText);
        }
        return new CommandCompactionPolicy(ratio, minDead);
    }
}
