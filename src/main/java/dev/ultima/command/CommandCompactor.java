package dev.ultima.command;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Packs zero-instance indirect commands out of a group while preserving the
 * relative order of survivors and rewriting owner indices.
 */
public final class CommandCompactor {
    public static final int COMMAND_BYTES = 20;

    public record Result(
            int commandsBefore,
            int commandsAfter,
            int bytesRewritten,
            double deadRatioBefore,
            boolean compacted) {
    }

    @FunctionalInterface
    public interface OwnerFixup {
        void accept(int oldIndex, int newIndex);
    }

    private CommandCompactor() {
    }

    public static Result compact(
            final int count,
            final int[] firstIndex,
            final int[] indexCount,
            final int[] baseVertex,
            final int[] sectionSlot,
            final int[] instanceCount,
            final Object[] owners,
            final OwnerFixup fixup,
            final BitSet dirty,
            final boolean force) {
        if (count < 0) {
            throw new IllegalArgumentException("count");
        }
        int live = 0;
        for (int i = 0; i < count; i++) {
            if (instanceCount[i] > 0) {
                live++;
            }
        }
        double deadRatio = count == 0 ? 0.0 : (double)(count - live) / (double)count;
        if (!force && live == count) {
            return new Result(count, count, 0, deadRatio, false);
        }
        int write = 0;
        for (int read = 0; read < count; read++) {
            if (instanceCount[read] <= 0) {
                if (fixup != null) {
                    fixup.accept(read, -1);
                }
                if (owners != null) {
                    owners[read] = null;
                }
                continue;
            }
            if (write != read) {
                firstIndex[write] = firstIndex[read];
                indexCount[write] = indexCount[read];
                baseVertex[write] = baseVertex[read];
                sectionSlot[write] = sectionSlot[read];
                instanceCount[write] = instanceCount[read];
                if (owners != null) {
                    owners[write] = owners[read];
                    owners[read] = null;
                }
            }
            if (fixup != null) {
                fixup.accept(read, write);
            }
            write++;
        }
        if (owners != null) {
            Arrays.fill(owners, write, count, null);
        }
        if (dirty != null) {
            dirty.clear();
            if (write > 0) {
                dirty.set(0, write);
            }
        }
        int bytes = write * COMMAND_BYTES;
        return new Result(count, write, bytes, deadRatio, write != count || force);
    }
}
