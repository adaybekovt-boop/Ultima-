package dev.ultima.client.renderer.snapshot;

import com.google.common.collect.ImmutableMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Extract-scoped intern of chunk block-entity maps. One {@code RenderRegionCache}
 * lives for a single extract pass; sections of the same chunk share one immutable
 * snapshot taken at first copy. Palettes stay per-section copies (stale-mesh boundary).
 */
public final class RenderSnapshotIntern {
    private static final ThreadLocal<IdentityHashMap<Map<BlockPos, BlockEntity>, Map<BlockPos, BlockEntity>>> BOUND =
            new ThreadLocal<>();

    private RenderSnapshotIntern() {
    }

    public static void bind(final IdentityHashMap<Map<BlockPos, BlockEntity>, Map<BlockPos, BlockEntity>> table) {
        BOUND.set(table);
    }

    public static void unbind() {
        BOUND.remove();
    }

    public static Map<BlockPos, BlockEntity> internBlockEntities(final Map<BlockPos, BlockEntity> live) {
        IdentityHashMap<Map<BlockPos, BlockEntity>, Map<BlockPos, BlockEntity>> table = BOUND.get();
        if (table == null) {
            return ImmutableMap.copyOf(live);
        }
        return table.computeIfAbsent(live, ImmutableMap::copyOf);
    }
}
