package dev.ultima.client.warmup;

import dev.ultima.config.LoadedModCache;
import dev.ultima.warmup.BudgetedWarmupPlan;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Profiler-only coordinator. No safe warmup adapter is active: reading already-initialized
 * {@code RenderTypes} getters does not prepare pipelines. Iris, GeckoLib, and ModernFix stay off.
 */
public final class WarmupCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-render-warmup");
    private static final long TOTAL_BUDGET_NANOS = positiveLong(
            "ultima.renderWarmupSystem.totalBudgetNanos", 100_000_000L);
    private static final String REQUESTED_MODE = System.getProperty("ultima.renderWarmupSystem.mode", "profile");
    private static final List<WarmupAdapter> ADAPTERS = List.of();
    private static final BudgetedWarmupPlan PLAN =
            new BudgetedWarmupPlan(ADAPTERS, TOTAL_BUDGET_NANOS, System::nanoTime);
    private static final List<AdapterStatus> PASSIVE_STATUSES = new ArrayList<>();

    private static volatile boolean failedOpen;
    private static volatile String failureReason = "";

    private WarmupCoordinator() {
    }

    public static void requestAfterResourceReload() {
        if (failedOpen) {
            return;
        }
        try {
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
            PASSIVE_STATUSES.add(new AdapterStatus(
                    "vanilla_static_render_types",
                    false,
                    "removed_noop",
                    "Static RenderType getter reads do not perform a useful first-use prepare."));
            PASSIVE_STATUSES.add(new AdapterStatus(
                    "active_warmup",
                    false,
                    "profiler_only",
                    "No safe warmup adapters are active."));
        } catch (Throwable throwable) {
            disable("resource_reload_schedule", throwable);
        }
    }

    /** Render-thread hook. There is no warmup work to pump. */
    public static void pump() {
        if (failedOpen || !PLAN.isPendingWork()) {
            return;
        }
    }

    public static void cancel(final String reason) {
        PLAN.cancel(reason);
    }

    public static boolean failedOpen() {
        return failedOpen;
    }

    public static String mode() {
        return "profiler_only";
    }

    public static String requestedMode() {
        return REQUESTED_MODE == null ? "profile" : REQUESTED_MODE;
    }

    public static String failClosedReason() {
        return "no_safe_warmup_adapters";
    }

    public static boolean changesRenderInitialization() {
        return false;
    }

    public static String failureReason() {
        return failureReason;
    }

    public static Snapshot snapshot() {
        BudgetedWarmupPlan.Snapshot plan = PLAN.snapshot();
        List<AdapterStatus> statuses = new ArrayList<>(PASSIVE_STATUSES);
        for (BudgetedWarmupPlan.AdapterResult result : plan.adapters()) {
            statuses.add(new AdapterStatus(result.id(), result.active(), result.state(), result.detail()));
        }
        return new Snapshot(
                "profiler_only",
                plan.completedAdapters(),
                plan.totalAdapters(),
                plan.discoveredItems(),
                plan.warmedItems(),
                plan.failures(),
                plan.warmupNanos(),
                0L,
                -1L,
                List.copyOf(statuses));
    }

    private static long positiveLong(final String key, final long fallback) {
        Long value = Long.getLong(key);
        return value != null && value > 0L ? value : fallback;
    }

    private static void disable(final String operation, final Throwable throwable) {
        if (!failedOpen) {
            failureReason = operation + ':' + throwable.getClass().getSimpleName();
            failedOpen = true;
            PLAN.cancel("failed_open:" + failureReason);
            LOGGER.warn("Render warmup failed open during {}; remaining work was cancelled.", operation, throwable);
        }
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
