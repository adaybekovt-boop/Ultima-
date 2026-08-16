package dev.ultima.retained;

import java.util.Arrays;
import java.util.BitSet;

/**
 * SoA indirect-command list with swap-remove. No GPU objects; {@code SubmitGroup}
 * owns the device buffer and copies these CPU records on dirty runs.
 *
 * <p>{@code instanceCount} 0/1 is visibility. Hidden commands stay in the list
 * until the owner is removed. Compaction is intentionally not implemented.
 */
public final class SubmitCommandList {
    public static final int INITIAL_CAPACITY = 16;

    private int count;
    private int[] firstIndex = new int[INITIAL_CAPACITY];
    private int[] indexCount = new int[INITIAL_CAPACITY];
    private int[] baseVertex = new int[INITIAL_CAPACITY];
    private int[] sectionSlot = new int[INITIAL_CAPACITY];
    private int[] instanceCount = new int[INITIAL_CAPACITY];
    private IndexedCommandOwner[] owners = new IndexedCommandOwner[INITIAL_CAPACITY];
    private final BitSet dirty = new BitSet();
    private boolean structureDirty;
    private int maxIndexCount;
    private int liveDraws;
    private int arrayReallocs;
    private int frameArrayReallocs;
    private int commandsAdded;
    private int commandsRemoved;
    private int visibilityToggles;

    public int add(
            final IndexedCommandOwner owner,
            final int firstIndex,
            final int indexCount,
            final int baseVertex,
            final int sectionSlot) {
        this.ensureCapacity(this.count + 1);
        int index = this.count++;
        this.firstIndex[index] = firstIndex;
        this.indexCount[index] = indexCount;
        this.baseVertex[index] = baseVertex;
        this.sectionSlot[index] = sectionSlot;
        this.instanceCount[index] = 1;
        this.owners[index] = owner;
        this.dirty.set(index);
        this.structureDirty = true;
        if (indexCount > this.maxIndexCount) {
            this.maxIndexCount = indexCount;
        }
        owner.onCommandAttached(index, 1);
        this.liveDraws++;
        this.commandsAdded++;
        return index;
    }

    public void updateImmutable(
            final IndexedCommandOwner owner,
            final int firstIndex,
            final int indexCount,
            final int baseVertex) {
        int index = owner.getCommandIndex();
        if (index < 0 || index >= this.count) {
            return;
        }
        this.firstIndex[index] = firstIndex;
        this.indexCount[index] = indexCount;
        this.baseVertex[index] = baseVertex;
        this.dirty.set(index);
        this.structureDirty = true;
        if (indexCount > this.maxIndexCount) {
            this.maxIndexCount = indexCount;
        }
    }

    public void setVisible(final IndexedCommandOwner owner, final boolean visible) {
        int index = owner.getCommandIndex();
        if (index < 0 || index >= this.count) {
            return;
        }
        int value = visible ? 1 : 0;
        if (this.instanceCount[index] == value) {
            owner.onCommandAttached(index, value);
            return;
        }
        this.instanceCount[index] = value;
        owner.onCommandAttached(index, value);
        this.liveDraws += visible ? 1 : -1;
        this.dirty.set(index);
        this.visibilityToggles++;
    }

    public void remove(final IndexedCommandOwner owner) {
        int index = owner.getCommandIndex();
        if (index < 0 || index >= this.count) {
            owner.onCommandDetached();
            return;
        }
        if (this.instanceCount[index] > 0) {
            this.liveDraws--;
        }
        int last = this.count - 1;
        if (index != last) {
            this.firstIndex[index] = this.firstIndex[last];
            this.indexCount[index] = this.indexCount[last];
            this.baseVertex[index] = this.baseVertex[last];
            this.sectionSlot[index] = this.sectionSlot[last];
            this.instanceCount[index] = this.instanceCount[last];
            this.owners[index] = this.owners[last];
            IndexedCommandOwner moved = this.owners[index];
            if (moved != null) {
                moved.onCommandMoved(index);
            }
            // GPU slot {@code index} now holds a different command; rewrite it.
            this.dirty.set(index);
        }
        this.owners[last] = null;
        this.count--;
        this.structureDirty = true;
        this.dirty.clear(last);
        owner.onCommandDetached();
        this.commandsRemoved++;
    }

    public void detachAll() {
        int previous = this.count;
        for (int i = 0; i < this.count; i++) {
            IndexedCommandOwner owner = this.owners[i];
            if (owner != null) {
                owner.onCommandDetached();
                this.owners[i] = null;
            }
        }
        this.commandsRemoved += previous;
        this.count = 0;
        this.liveDraws = 0;
        this.dirty.clear();
        this.structureDirty = true;
        this.maxIndexCount = 0;
    }

    public void resetFrameCounters() {
        this.commandsAdded = 0;
        this.commandsRemoved = 0;
        this.visibilityToggles = 0;
        this.frameArrayReallocs = 0;
    }

    public int count() {
        return this.count;
    }

    public int liveDraws() {
        return this.liveDraws;
    }

    public int hiddenZeroInstanceCommands() {
        return this.count - this.liveDraws;
    }

    public int capacity() {
        return this.firstIndex.length;
    }

    public int arrayReallocs() {
        return this.arrayReallocs;
    }

    public int frameArrayReallocs() {
        return this.frameArrayReallocs;
    }

    public int commandsAdded() {
        return this.commandsAdded;
    }

    public int commandsRemoved() {
        return this.commandsRemoved;
    }

    public int visibilityToggles() {
        return this.visibilityToggles;
    }

    public int maxIndexCount() {
        return this.maxIndexCount;
    }

    public boolean structureDirty() {
        return this.structureDirty;
    }

    public void clearStructureDirty() {
        this.structureDirty = false;
    }

    public void markAllDirty() {
        if (this.count > 0) {
            this.dirty.set(0, this.count);
        }
        this.structureDirty = true;
    }

    public BitSet dirty() {
        return this.dirty;
    }

    public boolean isDirty(final int index) {
        return this.dirty.get(index);
    }

    public int firstIndexAt(final int index) {
        return this.firstIndex[index];
    }

    public int indexCountAt(final int index) {
        return this.indexCount[index];
    }

    public int baseVertexAt(final int index) {
        return this.baseVertex[index];
    }

    public int sectionSlotAt(final int index) {
        return this.sectionSlot[index];
    }

    public int instanceCountAt(final int index) {
        return this.instanceCount[index];
    }

    public IndexedCommandOwner ownerAt(final int index) {
        return this.owners[index];
    }

    public double liveToTotalRatio() {
        return this.count == 0 ? 1.0 : (double)this.liveDraws / (double)this.count;
    }

    private void ensureCapacity(final int needed) {
        if (needed <= this.firstIndex.length) {
            return;
        }
        int size = Integer.highestOneBit(needed - 1) << 1;
        this.firstIndex = Arrays.copyOf(this.firstIndex, size);
        this.indexCount = Arrays.copyOf(this.indexCount, size);
        this.baseVertex = Arrays.copyOf(this.baseVertex, size);
        this.sectionSlot = Arrays.copyOf(this.sectionSlot, size);
        this.instanceCount = Arrays.copyOf(this.instanceCount, size);
        this.owners = Arrays.copyOf(this.owners, size);
        this.arrayReallocs++;
        this.frameArrayReallocs++;
    }
}
