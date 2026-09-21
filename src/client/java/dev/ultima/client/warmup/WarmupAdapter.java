package dev.ultima.client.warmup;

import dev.ultima.warmup.BudgetedWarmupPlan;

/** One independently fail-open, render-thread warmup unit. */
interface WarmupAdapter extends BudgetedWarmupPlan.Adapter {
}
