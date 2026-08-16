package dev.ultima.review;

import dev.ultima.command.CommandCompactionMetrics;
import dev.ultima.command.CommandCompactor;
import dev.ultima.lab.CommandCompactionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

final class CommandCompactionChecks {
    private CommandCompactionChecks() {
    }

    static void run() {
        testAllLiveDoesNotRewrite();
        testInterleavedDeadPreservesLiveOrder();
        testOwnerIndicesAndFirstInstance();
        testPolicyGatesCompaction();
        testRandomizedOracle();
        testMetrics();
    }

    private static void testAllLiveDoesNotRewrite() {
        Tape tape = Tape.of(
                cmd(10, 1, 0, 0, 3),
                cmd(20, 1, 6, 4, 7));
        CommandCompactor.Result result = tape.compact(false);
        if (result.compacted() || result.commandsAfter() != 2 || tape.sectionSlot[1] != 7) {
            throw new AssertionError("all-live tape must stay put: " + result);
        }
    }

    private static void testInterleavedDeadPreservesLiveOrder() {
        Tape tape = Tape.of(
                cmd(3, 0, 0, 0, 1),
                cmd(6, 1, 12, 4, 2),
                cmd(9, 0, 18, 8, 3),
                cmd(12, 1, 24, 12, 4),
                cmd(15, 0, 30, 16, 5));
        int[] ownerBefore = tape.ownerIndex.clone();
        CommandCompactor.Result result = tape.compact(true);
        if (result.commandsBefore() != 5 || result.commandsAfter() != 2) {
            throw new AssertionError("dead commands must be packed out: " + result);
        }
        if (tape.sectionSlot[0] != 2 || tape.sectionSlot[1] != 4) {
            throw new AssertionError("live firstInstance order must be preserved");
        }
        if (tape.firstIndex[0] != 12 || tape.indexCount[1] != 12 || tape.baseVertex[1] != 12) {
            throw new AssertionError("firstIndex/indexCount/baseVertex must follow the live commands");
        }
        if (tape.instanceCount[0] != 1 || tape.instanceCount[1] != 1) {
            throw new AssertionError("survivors stay visible");
        }
        if (tape.ownerIndex[ownerBefore[1]] != 0 || tape.ownerIndex[ownerBefore[3]] != 1) {
            throw new AssertionError("owner commandIndex rewrite");
        }
        if (tape.ownerIndex[ownerBefore[0]] != -1 || tape.ownerIndex[ownerBefore[2]] != -1) {
            throw new AssertionError("dropped owners must clear commandIndex");
        }
        if (!tape.dirty.get(0) || !tape.dirty.get(1) || tape.dirty.get(2)) {
            throw new AssertionError("dirty range is the compacted live prefix");
        }
    }

    private static void testOwnerIndicesAndFirstInstance() {
        Tape tape = Tape.of(cmd(1, 0, 0, 0, 99), cmd(2, 1, 3, 1, 100));
        tape.compact(true);
        if (tape.count() != 1 || tape.sectionSlot[0] != 100 || tape.ownerIndex[1] != 0) {
            throw new AssertionError("hidden then live");
        }
    }

    private static void testPolicyGatesCompaction() {
        CommandCompactionPolicy policy = new CommandCompactionPolicy(0.5, 3);
        if (policy.shouldCompact(4, 3)) {
            throw new AssertionError("1 dead below minDead 3");
        }
        if (!policy.shouldCompact(8, 3)) {
            throw new AssertionError("5/8 dead must compact");
        }
        CommandCompactionPolicy explicit = new CommandCompactionPolicy(0.0, 0);
        if (!explicit.shouldCompact(2, 1)) {
            throw new AssertionError("explicit 0/0 policy compacts any dead");
        }
        if (explicit.shouldCompact(2, 2)) {
            throw new AssertionError("zero dead still does not compact");
        }
    }

    private static void testRandomizedOracle() {
        Random random = new Random(0x434F4D50414354L);
        for (int trial = 0; trial < 500; trial++) {
            int count = random.nextInt(48);
            Cmd[] cmds = new Cmd[count];
            for (int i = 0; i < count; i++) {
                cmds[i] = cmd(
                        random.nextInt(64) + 1,
                        random.nextBoolean() ? 1 : 0,
                        random.nextInt(128),
                        random.nextInt(64),
                        i + 1000);
            }
            Tape tape = Tape.of(cmds);
            List<Cmd> oracle = new ArrayList<>();
            for (Cmd cmd : cmds) {
                if (cmd.instanceCount > 0) {
                    oracle.add(cmd);
                }
            }
            tape.compact(true);
            if (tape.count() != oracle.size()) {
                throw new AssertionError("oracle size trial=" + trial);
            }
            for (int i = 0; i < oracle.size(); i++) {
                Cmd expected = oracle.get(i);
                if (tape.indexCount[i] != expected.indexCount
                        || tape.instanceCount[i] != expected.instanceCount
                        || tape.firstIndex[i] != expected.firstIndex
                        || tape.baseVertex[i] != expected.baseVertex
                        || tape.sectionSlot[i] != expected.sectionSlot) {
                    throw new AssertionError("oracle mismatch trial=" + trial + " i=" + i);
                }
            }
        }
    }

    private static void testMetrics() {
        CommandCompactionMetrics.reset();
        Tape tape = Tape.of(cmd(4, 0, 0, 0, 1), cmd(4, 1, 4, 0, 2));
        long start = System.nanoTime();
        CommandCompactor.Result result = tape.compact(true);
        CommandCompactionMetrics.record(result, Math.max(1L, System.nanoTime() - start));
        CommandCompactionMetrics.Snapshot snapshot = CommandCompactionMetrics.snapshot();
        if (snapshot.compactionCount() != 1
                || snapshot.commandsBefore() != 2
                || snapshot.commandsAfter() != 1
                || snapshot.bytesRewritten() != CommandCompactor.COMMAND_BYTES
                || snapshot.maxDeadRatio() < 0.49) {
            throw new AssertionError("compaction metrics " + snapshot);
        }
    }

    private static Cmd cmd(
            final int indexCount,
            final int instanceCount,
            final int firstIndex,
            final int baseVertex,
            final int sectionSlot) {
        return new Cmd(indexCount, instanceCount, firstIndex, baseVertex, sectionSlot);
    }

    private record Cmd(int indexCount, int instanceCount, int firstIndex, int baseVertex, int sectionSlot) {
    }

    private static final class Tape {
        private final int[] firstIndex;
        private final int[] indexCount;
        private final int[] baseVertex;
        private final int[] sectionSlot;
        private final int[] instanceCount;
        private final Integer[] owners;
        private final int[] ownerIndex;
        private final BitSet dirty = new BitSet();
        private int count;

        private Tape(final Cmd[] cmds) {
            this.count = cmds.length;
            this.firstIndex = new int[cmds.length];
            this.indexCount = new int[cmds.length];
            this.baseVertex = new int[cmds.length];
            this.sectionSlot = new int[cmds.length];
            this.instanceCount = new int[cmds.length];
            this.owners = new Integer[cmds.length];
            this.ownerIndex = new int[cmds.length];
            Arrays.fill(this.ownerIndex, -1);
            for (int i = 0; i < cmds.length; i++) {
                this.firstIndex[i] = cmds[i].firstIndex;
                this.indexCount[i] = cmds[i].indexCount;
                this.baseVertex[i] = cmds[i].baseVertex;
                this.sectionSlot[i] = cmds[i].sectionSlot;
                this.instanceCount[i] = cmds[i].instanceCount;
                this.owners[i] = i;
                this.ownerIndex[i] = i;
            }
        }

        private static Tape of(final Cmd... cmds) {
            return new Tape(cmds);
        }

        private int count() {
            return this.count;
        }

        private CommandCompactor.Result compact(final boolean force) {
            CommandCompactor.Result result = CommandCompactor.compact(
                    this.count,
                    this.firstIndex,
                    this.indexCount,
                    this.baseVertex,
                    this.sectionSlot,
                    this.instanceCount,
                    this.owners,
                    (from, to) -> {
                        if (to < 0) {
                            Integer owner = this.owners[from];
                            if (owner != null) {
                                this.ownerIndex[owner] = -1;
                            }
                        } else {
                            Integer owner = this.owners[to];
                            if (owner == null && from == to) {
                                owner = this.owners[from];
                            }
                            if (owner != null) {
                                this.ownerIndex[owner] = to;
                            }
                        }
                    },
                    this.dirty,
                    force);
            this.count = result.commandsAfter();
            return result;
        }
    }
}
