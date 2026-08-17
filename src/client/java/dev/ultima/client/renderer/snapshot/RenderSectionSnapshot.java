package dev.ultima.client.renderer.snapshot;

import dev.ultima.meshing.BlockRenderFlags;
import dev.ultima.meshing.PackedSectionVolume;
import dev.ultima.meshing.SectionIndex;
import java.util.Arrays;
import java.util.IdentityHashMap;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;
import org.jspecify.annotations.Nullable;

/**
 * Immutable-during-compile 18³ snapshot of a {@link RenderSectionRegion}.
 * Light and biome tint stay on the original region so vanilla lighting is exact.
 * Positions outside the halo fall through to the region (AO corner samples).
 */
public final class RenderSectionSnapshot implements BlockAndTintGetter {
    private final PackedSectionVolume volume = new PackedSectionVolume();
    private final IdentityHashMap<BlockState, Integer> intern = new IdentityHashMap<>();
    private BlockState[] palette = new BlockState[16];
    private int paletteSize;
    private BlockEntity[] entities = new BlockEntity[8];
    private RenderSectionRegion region;
    private int minY;
    private int height;

    public PackedSectionVolume volume() {
        return this.volume;
    }

    public void capture(final RenderSectionRegion region, final int originX, final int originY, final int originZ, final MutableBlockPos cursor) {
        this.region = region;
        this.minY = region.getMinY();
        this.height = region.getHeight();
        this.volume.begin(originX, originY, originZ);
        this.intern.clear();
        this.palette[0] = Blocks.AIR.defaultBlockState();
        this.paletteSize = 1;
        this.intern.put(this.palette[0], PackedSectionVolume.AIR_ID);
        Arrays.fill(this.entities, null);

        for (int z = 0; z < SectionIndex.EXTENT; z++) {
            int worldZ = originZ - SectionIndex.HALO + z;
            for (int y = 0; y < SectionIndex.EXTENT; y++) {
                int worldY = originY - SectionIndex.HALO + y;
                for (int x = 0; x < SectionIndex.EXTENT; x++) {
                    int worldX = originX - SectionIndex.HALO + x;
                    cursor.set(worldX, worldY, worldZ);
                    BlockState state = region.getBlockState(cursor);
                    int packed = SectionIndex.packed(x, y, z);
                    int stateId = this.internState(state);
                    int flags = flagsOf(state);
                    this.volume.setCell(packed, stateId, flags);
                    if (BlockRenderFlags.hasBlockEntity(flags) && SectionIndex.inInterior(x - 1, y - 1, z - 1)) {
                        BlockEntity blockEntity = region.getBlockEntity(cursor);
                        if (blockEntity != null) {
                            int slot = this.volume.addBlockEntity(packed);
                            if (slot >= this.entities.length) {
                                this.entities = Arrays.copyOf(this.entities, this.entities.length * 2);
                            }
                            this.entities[slot] = blockEntity;
                        }
                    }
                }
            }
        }
        this.volume.freeze();
    }

    public BlockState state(final int packedIndex) {
        return this.palette[this.volume.state(packedIndex)];
    }

    public @Nullable BlockEntity entity(final int slot) {
        return slot >= 0 && slot < this.entities.length ? this.entities[slot] : null;
    }

    private int internState(final BlockState state) {
        Integer existing = this.intern.get(state);
        if (existing != null) {
            return existing;
        }
        if (this.paletteSize == this.palette.length) {
            this.palette = Arrays.copyOf(this.palette, this.paletteSize * 2);
        }
        int id = this.paletteSize++;
        this.palette[id] = state;
        this.intern.put(state, id);
        return id;
    }

    /**
     * Production hot-path flags. Only bits {@code HybridSectionMesher} reads:
     * air, solid render, block-entity, fluid, model. Does <em>not</em> call
     * {@code getFaceOcclusionShape} or {@code skipRendering} — those are
     * test-fixture heuristics and are computed by {@link #flagsOfForTest}.
     */
    public static int flagsOf(final BlockState state) {
        boolean air = state.isAir();
        return BlockRenderFlags.pack(
                air,
                !air && state.isSolidRender(),
                !air && state.hasBlockEntity(),
                !air && !state.getFluidState().isEmpty(),
                !air && state.getRenderShape() == RenderShape.MODEL,
                false,
                false,
                false);
    }

    /**
     * Test/debug only. Walks all six faces for occlusion / skip bits. Never
     * called from {@link #capture} or {@code HybridSectionMesher.compile}.
     */
    public static int flagsOfForTest(final BlockState state) {
        boolean air = state.isAir();
        boolean solid = !air && state.isSolidRender();
        boolean full = !air;
        boolean empty = air;
        boolean skip = !air;
        if (!air) {
            for (Direction direction : Direction.values()) {
                var shape = state.getFaceOcclusionShape(direction);
                full &= shape == Shapes.block();
                empty &= shape == Shapes.empty();
                skip &= state.skipRendering(state, direction);
            }
        }
        return BlockRenderFlags.pack(
                air,
                solid,
                !air && state.hasBlockEntity(),
                !air && !state.getFluidState().isEmpty(),
                !air && state.getRenderShape() == RenderShape.MODEL,
                full,
                empty,
                skip);
    }

    private int packedIndex(final BlockPos pos) {
        int localX = pos.getX() - this.volume.originX() + SectionIndex.HALO;
        int localY = pos.getY() - this.volume.originY() + SectionIndex.HALO;
        int localZ = pos.getZ() - this.volume.originZ() + SectionIndex.HALO;
        if (!SectionIndex.inExtent(localX, localY, localZ)) {
            return -1;
        }
        return SectionIndex.packed(localX, localY, localZ);
    }

    @Override
    public BlockState getBlockState(final BlockPos pos) {
        int packed = this.packedIndex(pos);
        if (packed >= 0) {
            return this.state(packed);
        }
        return this.region.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(final BlockPos pos) {
        return this.getBlockState(pos).getFluidState();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(final BlockPos pos) {
        int packed = this.packedIndex(pos);
        if (packed >= 0) {
            return this.entity(this.volume.blockEntitySlot(packed));
        }
        return this.region.getBlockEntity(pos);
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return this.region.cardinalLighting();
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return this.region.getLightEngine();
    }

    @Override
    public int getBlockTint(final BlockPos pos, final ColorResolver resolver) {
        return this.region.getBlockTint(pos, resolver);
    }

    @Override
    public int getMinY() {
        return this.minY;
    }

    @Override
    public int getHeight() {
        return this.height;
    }
}
