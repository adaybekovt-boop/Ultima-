package dev.ultima.mixin.render_snapshot;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.client.renderer.snapshot.RenderSnapshotIntern;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RenderRegionCache.class)
public abstract class RenderRegionCacheMixin {
    @Unique
    private final IdentityHashMap<Map<BlockPos, BlockEntity>, Map<BlockPos, BlockEntity>> ultima$blockEntitySnapshots =
            new IdentityHashMap<>();

    @WrapMethod(method = "createRegion")
    private RenderSectionRegion ultimaInternSnapshotsForRegion(
            final ClientLevel level,
            final long sectionNode,
            final Operation<RenderSectionRegion> original) {
        RenderSnapshotIntern.bind(this.ultima$blockEntitySnapshots);
        try {
            return original.call(level, sectionNode);
        } finally {
            RenderSnapshotIntern.unbind();
        }
    }
}
