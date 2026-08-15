package dev.ultima.mixin.render_snapshot;

import com.google.common.collect.ImmutableMap;
import dev.ultima.client.renderer.snapshot.RenderSnapshotIntern;
import java.util.Map;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SectionCopy.class)
public abstract class SectionCopyMixin {
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/ImmutableMap;copyOf(Ljava/util/Map;)Lcom/google/common/collect/ImmutableMap;"))
    private ImmutableMap<BlockPos, BlockEntity> ultimaInternBlockEntities(final Map<BlockPos, BlockEntity> live) {
        Map<BlockPos, BlockEntity> interned = RenderSnapshotIntern.internBlockEntities(live);
        if (interned instanceof ImmutableMap<BlockPos, BlockEntity> immutable) {
            return immutable;
        }
        return ImmutableMap.copyOf(interned);
    }
}
