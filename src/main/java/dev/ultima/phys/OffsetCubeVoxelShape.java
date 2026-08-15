package dev.ultima.phys;

import it.unimi.dsi.fastutil.doubles.AbstractDoubleList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * World-space full cube {@code [x, x+1] × [y, y+1] × [z, z+1]} with the same discrete occupancy
 * and coordinate lists as {@code Shapes.block().move(x, y, z)}, but without an
 * {@code ArrayVoxelShape} plus three {@code OffsetDoubleList} and {@code CubePointRange} objects
 * per call.
 */
public final class OffsetCubeVoxelShape extends VoxelShape {
    private static final DiscreteVoxelShape UNIT = createUnit();

    private final int offsetX;
    private final int offsetY;
    private final int offsetZ;
    private final DoubleList xs;
    private final DoubleList ys;
    private final DoubleList zs;

    public OffsetCubeVoxelShape(final int offsetX, final int offsetY, final int offsetZ) {
        super(UNIT);
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.xs = new OffsetCubeCoords(offsetX);
        this.ys = new OffsetCubeCoords(offsetY);
        this.zs = new OffsetCubeCoords(offsetZ);
    }

    public static boolean isExactInt(final double value) {
        return value == (double)((int)value);
    }

    @Override
    public DoubleList getCoords(final Direction.Axis axis) {
        return switch (axis) {
            case X -> this.xs;
            case Y -> this.ys;
            case Z -> this.zs;
        };
    }

    @Override
    protected int findIndex(final Direction.Axis axis, final double coord) {
        // Same search as VoxelShape.findIndex on a size-1 ArrayVoxelShape with coords [o, o+1].
        return Mth.binarySearch(0, 2, index -> coord < this.get(axis, index)) - 1;
    }

    @Override
    public VoxelShape move(final double dx, final double dy, final double dz) {
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
            return this;
        }
        if (isExactInt(dx) && isExactInt(dy) && isExactInt(dz)) {
            return new OffsetCubeVoxelShape(
                    this.offsetX + (int)dx, this.offsetY + (int)dy, this.offsetZ + (int)dz);
        }
        return super.move(dx, dy, dz);
    }

    private static DiscreteVoxelShape createUnit() {
        BitSetDiscreteVoxelShape shape = new BitSetDiscreteVoxelShape(1, 1, 1);
        shape.fill(0, 0, 0);
        return shape;
    }

    private static final class OffsetCubeCoords extends AbstractDoubleList {
        private final int origin;

        private OffsetCubeCoords(final int origin) {
            this.origin = origin;
        }

        @Override
        public double getDouble(final int index) {
            return this.origin + index;
        }

        @Override
        public int size() {
            return 2;
        }
    }
}
