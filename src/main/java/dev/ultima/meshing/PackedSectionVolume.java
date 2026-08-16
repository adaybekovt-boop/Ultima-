package dev.ultima.meshing;

import java.util.Arrays;

/**
 * Versioned packed 18³ volume. State storage is palette indices (0 = air).
 * The volume is mutated only by {@link #begin} / {@link #setCell} during snapshot
 * build; tessellation treats a finished version as immutable.
 */
public final class PackedSectionVolume {
    public static final int AIR_ID = 0;
    private static final int INITIAL_ENTITIES = 16;

    private long version;
    private int originX;
    private int originY;
    private int originZ;
    private final int[] states = new int[SectionIndex.VOLUME];
    private final byte[] flags = new byte[SectionIndex.VOLUME];
    private final int[] blockEntitySlot = new int[SectionIndex.VOLUME];
    private int[] entityPackedIndex = new int[INITIAL_ENTITIES];
    private int entityCount;
    private boolean frozen;

    public PackedSectionVolume() {
        Arrays.fill(this.blockEntitySlot, -1);
    }

    public void begin(final int originX, final int originY, final int originZ) {
        this.version++;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        Arrays.fill(this.states, AIR_ID);
        Arrays.fill(this.flags, (byte)BlockRenderFlags.AIR);
        Arrays.fill(this.blockEntitySlot, -1);
        this.entityCount = 0;
        this.frozen = false;
    }

    public void freeze() {
        this.frozen = true;
    }

    public boolean frozen() {
        return this.frozen;
    }

    public long version() {
        return this.version;
    }

    public int originX() {
        return this.originX;
    }

    public int originY() {
        return this.originY;
    }

    public int originZ() {
        return this.originZ;
    }

    public void setCell(final int packedIndex, final int stateId, final byte flags) {
        this.requireMutable();
        this.states[packedIndex] = stateId;
        this.flags[packedIndex] = flags;
    }

    public int addBlockEntity(final int packedIndex) {
        this.requireMutable();
        if (this.entityCount == this.entityPackedIndex.length) {
            this.entityPackedIndex = Arrays.copyOf(this.entityPackedIndex, this.entityCount * 2);
        }
        int slot = this.entityCount++;
        this.entityPackedIndex[slot] = packedIndex;
        this.blockEntitySlot[packedIndex] = slot;
        return slot;
    }

    public int state(final int packedIndex) {
        return this.states[packedIndex];
    }

    public int flags(final int packedIndex) {
        return this.flags[packedIndex];
    }

    public int blockEntitySlot(final int packedIndex) {
        return this.blockEntitySlot[packedIndex];
    }

    public int entityCount() {
        return this.entityCount;
    }

    public int entityPackedIndex(final int slot) {
        return this.entityPackedIndex[slot];
    }

    public int neighborState(final int interiorX, final int interiorY, final int interiorZ, final int dx, final int dy, final int dz) {
        return this.states[SectionIndex.neighbor(interiorX, interiorY, interiorZ, dx, dy, dz)];
    }

    public int neighborFlags(final int interiorX, final int interiorY, final int interiorZ, final int dx, final int dy, final int dz) {
        return this.flags[SectionIndex.neighbor(interiorX, interiorY, interiorZ, dx, dy, dz)];
    }

    private void requireMutable() {
        if (this.frozen) {
            throw new IllegalStateException("packed section volume " + this.version + " is frozen");
        }
    }
}
