package dev.ultima.review;

import dev.ultima.retained.IndexedCommandOwner;
import dev.ultima.retained.SubmitCommandList;
import java.util.ArrayList;
import java.util.List;

/** Pure regression checks for the recovered Prompt #2.4 bounded compaction contract. */
public final class CommandCompactionRecoveryTest {
    private CommandCompactionRecoveryTest() {
    }

    public static void main(final String[] args) {
        testDeadRatioTriggerAndOwnerRewrite();
        testTotalCountTrigger();
        testDroppedOwnerCanReattach();
        System.out.println("Command compaction recovery checks passed.");
    }

    private static void testDeadRatioTriggerAndOwnerRewrite() {
        SubmitCommandList list = new SubmitCommandList();
        Owner[] owners = new Owner[6];
        for (int i = 0; i < owners.length; i++) {
            owners[i] = new Owner();
            list.add(owners[i], 100 + i, 10 + i, 200 + i, 300 + i);
        }

        list.setVisible(owners[1], false);
        list.setVisible(owners[3], false);
        list.setVisible(owners[4], false);
        if (list.shouldCompactBounded()) {
            throw new AssertionError("exactly 50% hidden must not trigger the strict >50% policy");
        }
        list.setVisible(owners[5], false);
        if (!list.shouldCompactBounded()) {
            throw new AssertionError("more than 50% hidden must trigger bounded compaction");
        }

        List<IndexedCommandOwner> dropped = new ArrayList<>();
        SubmitCommandList.CompactionResult result = list.compactHidden(dropped::add);
        if (!result.compacted() || result.commandsBefore() != 6 || result.commandsAfter() != 2 || result.hiddenRemoved() != 4) {
            throw new AssertionError("unexpected compaction result: " + result);
        }
        if (list.count() != 2 || list.liveDraws() != 2 || list.hiddenZeroInstanceCommands() != 0) {
            throw new AssertionError("compacted population must contain live commands only");
        }
        if (owners[0].getCommandIndex() != 0 || owners[2].getCommandIndex() != 1) {
            throw new AssertionError("surviving owners must be rewritten to compact indices");
        }
        if (list.firstIndexAt(0) != 100 || list.firstIndexAt(1) != 102) {
            throw new AssertionError("live command relative order must be preserved");
        }
        if (dropped.size() != 4) {
            throw new AssertionError("dropped-owner callback count: " + dropped.size());
        }
        for (int index : new int[] {1, 3, 4, 5}) {
            if (owners[index].getCommandIndex() != -1 || owners[index].instanceCount != 0) {
                throw new AssertionError("dropped owner " + index + " must be detached");
            }
        }
        if (!list.isDirty(0) || !list.isDirty(1)) {
            throw new AssertionError("all compacted live records must be rewritten to the replacement GPU buffer");
        }
    }

    private static void testTotalCountTrigger() {
        SubmitCommandList list = new SubmitCommandList();
        Owner[] owners = new Owner[SubmitCommandList.BOUNDED_TOTAL_TRIGGER + 1];
        for (int i = 0; i < owners.length; i++) {
            owners[i] = new Owner();
            list.add(owners[i], i, 6, i, i);
        }
        list.setVisible(owners[owners.length - 1], false);
        if (!list.shouldCompactBounded()) {
            throw new AssertionError("total command population >4096 with hidden records must trigger compaction");
        }
        SubmitCommandList.CompactionResult result = list.compactHidden(owner -> { });
        if (!result.compacted() || list.count() != SubmitCommandList.BOUNDED_TOTAL_TRIGGER) {
            throw new AssertionError("4097->4096 bounded compaction failed: " + result);
        }
    }

    private static void testDroppedOwnerCanReattach() {
        SubmitCommandList list = new SubmitCommandList();
        Owner a = new Owner();
        Owner b = new Owner();
        Owner c = new Owner();
        list.add(a, 1, 6, 1, 1);
        list.add(b, 2, 6, 2, 2);
        list.add(c, 3, 6, 3, 3);
        list.setVisible(a, false);
        list.setVisible(b, false);
        list.compactHidden(owner -> { });
        if (a.getCommandIndex() != -1) {
            throw new AssertionError("hidden owner must detach during compaction");
        }
        int reattached = list.add(a, 9, 12, 9, 9);
        if (a.getCommandIndex() != reattached || a.instanceCount != 1) {
            throw new AssertionError("a previously compacted owner must be attachable when visible again");
        }
    }

    private static final class Owner implements IndexedCommandOwner {
        private int commandIndex = -1;
        private int instanceCount;

        @Override
        public int getCommandIndex() {
            return this.commandIndex;
        }

        @Override
        public void onCommandAttached(final int commandIndex, final int instanceCount) {
            this.commandIndex = commandIndex;
            this.instanceCount = instanceCount;
        }

        @Override
        public void onCommandMoved(final int newCommandIndex) {
            this.commandIndex = newCommandIndex;
        }

        @Override
        public void onCommandDetached() {
            this.commandIndex = -1;
            this.instanceCount = 0;
        }
    }
}
