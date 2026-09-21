package dev.ultima.client.warmup;

import dev.ultima.warmup.BudgetedWarmupPlan;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/** Curated static-only touch set: no world, entity, item, animation, packet, or GL side effects. */
final class VanillaRenderTypeWarmupAdapter implements WarmupAdapter {
    private static final List<Supplier<RenderType>> TOUCHES = List.of(
            RenderTypes::solidMovingBlock,
            RenderTypes::cutoutMovingBlock,
            RenderTypes::translucentMovingBlock,
            RenderTypes::leash,
            RenderTypes::waterMask,
            RenderTypes::armorEntityGlint,
            RenderTypes::glintTranslucent,
            RenderTypes::glint,
            RenderTypes::entityGlint,
            RenderTypes::textBackground,
            RenderTypes::textBackgroundSeeThrough,
            RenderTypes::lightning,
            RenderTypes::dragonRays,
            RenderTypes::endPortal,
            RenderTypes::endGateway,
            RenderTypes::lines,
            RenderTypes::linesTranslucent,
            RenderTypes::secondaryBlockOutline);

    private int nextTouch;

    @Override
    public String id() {
        return "vanilla_static_render_types";
    }

    @Override
    public BudgetedWarmupPlan.Support supports() {
        return new BudgetedWarmupPlan.Support(
                true, "Curated no-argument RenderTypes accessors; static CPU initialization only.");
    }

    @Override
    public int discover() {
        this.nextTouch = 0;
        return TOUCHES.size();
    }

    @Override
    public BudgetedWarmupPlan.WarmResult warm(final long deadlineNanos) {
        int warmed = 0;
        while (this.nextTouch < TOUCHES.size() && System.nanoTime() < deadlineNanos) {
            TOUCHES.get(this.nextTouch++).get();
            warmed++;
        }
        return new BudgetedWarmupPlan.WarmResult(warmed, this.nextTouch >= TOUCHES.size());
    }

    @Override
    public long timeoutNanos() {
        return 25_000_000L;
    }
}
