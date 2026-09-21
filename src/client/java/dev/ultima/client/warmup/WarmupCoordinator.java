package dev.ultima.client.warmup;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.ultima.config.LoadedModCache;
import dev.ultima.warmup.BudgetedWarmupPlan;
import java.util.ArrayList;
import java.util.List;

/** Budgeted render-thread warmup plan. Unsupported mod adapters are explicit diagnostics. */
public final class WarmupCoordinator {
    private static final long TOTAL_BUDGET_NANOS = positiveLong(
            "ultima.renderWarmupSystem.totalBudgetNanos", 100_000_000L);
    private static final long FRAME_BUDGET_NANOS = positiveLong(
            "ultima.renderWarmupSystem.frameBudgetNanos", 2_000_000L);
    private static final List<WarmupAdapter> ADAPTERS = List.of(new VanillaRenderTypeWarmupAdapter());
    private static final BudgetedWarmupPlan PLAN =
            new BudgetedWarmupPlan(ADAPTERS, TOTAL_BUDGET_NANOS, System::nanoTime);
    private static final List<AdapterStatus> PASSIVE_STATUSES = new ArrayList<>();

    private static boolean planStarted;
    private static boolean completionPublished;
    private static long memoryBefore;
    private static long memoryAfter;

    private WarmupCoordinator() {
    }

    public static void requestAfterResourceReload() {
        PLAN.request();
        planStarted = false;
        completionPublished = false;
        memoryBefore = 0L;
        memoryAfter = 0L;
        PASSIVE_STATUSES.clear();
        PASSIVE_STATUSES.add(new AdapterStatus(
                "iris_programs",
                false,
                "disabled_eager_compile_path",
                "Iris 1.11.4 compiles programs during pipeline creation; no fake lazy-program warmup."));
        PASSIVE_STATUSES.add(new AdapterStatus(
                "geckolib",
                false,
                LoadedModCache.isLoaded("geckolib") ? "disabled_no_state_safe_public_surface" : "mod_absent",
                "No fake entity/world/model invocation is performed."));
        PASSIVE_STATUSES.add(new AdapterStatus(
                "modernfix_dynamic_resources",
                false,
                LoadedModCache.isLoaded("modernfix") ? "disabled_no_versioned_selective_api" : "mod_absent",
                "Ultima does not preload all models or erase ModernFix's memory/startup benefit."));
    }

    /** Runs on the render thread at frame start. */
    public static void pump() {
        if (!PLAN.isPendingWork() || !RenderSystem.isOnRenderThread()) {
            return;
        }
        if (!planStarted) {
            planStarted = true;
            memoryBefore = usedMemory();
        }
        FirstUseProfiler.beginWarmup();
        try {
            PLAN.pump(FRAME_BUDGET_NANOS);
        } finally {
            FirstUseProfiler.endWarmup();
        }
        if (PLAN.isComplete() && !completionPublished) {
            completionPublished = true;
            memoryAfter = usedMemory();
            FirstUseProfiler.markWarmupCompleted();
        }
    }

    public static void cancel(final String reason) {
        PLAN.cancel(reason);
    }

    public static Snapshot snapshot() {
        BudgetedWarmupPlan.Snapshot plan = PLAN.snapshot();
        List<AdapterStatus> statuses = new ArrayList<>(PASSIVE_STATUSES);
        for (BudgetedWarmupPlan.AdapterResult result : plan.adapters()) {
            statuses.add(new AdapterStatus(result.id(), result.active(), result.state(), result.detail()));
        }
        return new Snapshot(
                plan.state(),
                plan.completedAdapters(),
                plan.totalAdapters(),
                plan.discoveredItems(),
                plan.warmedItems(),
                plan.failures(),
                plan.warmupNanos(),
                memoryAfter == 0L ? 0L : memoryAfter - memoryBefore,
                -1L,
                List.copyOf(statuses));
    }

    private static long usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long positiveLong(final String key, final long fallback) {
        Long value = Long.getLong(key);
        return value != null && value > 0L ? value : fallback;
    }

    public record AdapterStatus(String id, boolean active, String state, String detail) {
    }

    public record Snapshot(
            String state,
            int completedAdapters,
            int totalAdapters,
            int discoveredItems,
            int warmedItems,
            int failures,
            long warmupNanos,
            long memoryDeltaBytes,
            long gpuResourceDelta,
            List<AdapterStatus> adapters) {
    }
}
