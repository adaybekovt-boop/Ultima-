package dev.ultima.mixin.render_snapshot;

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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderRegionCache.class)
public abstract class RenderRegionCacheMixin {
    @Unique
    private final IdentityHashMap<Map<BlockPos, BlockEntity>, Map<BlockPos, BlockEntity>> ultima$blockEntitySnapshots =
            new IdentityHashMap<>();

    @Inject(method = "createRegion", at = @At("HEAD"))
    private void ultimaBindSnapshotIntern(final ClientLevel level, final long sectionNode, final CallbackInfoReturnable<RenderSectionRegion> cir) {
        RenderSnapshotIntern.bind(this.ultima$blockEntitySnapshots);
    }

    @Inject(method = "createRegion", at = @At("RETURN"))
    private void ultimaUnbindSnapshotIntern(final ClientLevel level, final long sectionNode, final CallbackInfoReturnable<RenderSectionRegion> cir) {
        RenderSnapshotIntern.unbind();
    }
}
