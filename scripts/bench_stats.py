#!/usr/bin/env python3
"""Paired A/B statistics and the default-decision rule used by the benchmark scripts.

A "gain" is the percentage by which the ON side beats the OFF side on a metric where lower is
better (milliseconds per tick, allocated bytes): gain = (off - on) / off * 100. A positive gain
means Ultima helped. Pairs are run in alternating order, so each pair is one independent sample.
"""
from __future__ import annotations

import math
import sys
from dataclasses import dataclass

THRESHOLD_PERCENT = 3.0
MIN_PAIRS = 6

# Two-sided 95% critical values of Student's t, by degrees of freedom.
_T_CRIT_95 = {
    1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262,
    10: 2.228, 11: 2.201, 12: 2.179, 13: 2.160, 14: 2.145, 15: 2.131, 16: 2.120, 17: 2.110,
    18: 2.101, 19: 2.093, 20: 2.086, 25: 2.060, 30: 2.042, 40: 2.021, 60: 2.000, 120: 1.980,
}


def t_crit(df: int) -> float:
    """Critical value for the largest tabulated df not above ``df`` (slightly conservative)."""
    if df < 1:
        raise ValueError("need at least two pairs for a confidence interval")
    usable = [key for key in _T_CRIT_95 if key <= df]
    return _T_CRIT_95[max(usable)]


def gain_percent(off: float, on: float) -> float:
    if off <= 0:
        raise ValueError(f"OFF value must be positive, got {off}")
    return (off - on) / off * 100.0


@dataclass(frozen=True)
class Interval:
    n: int
    mean: float
    sd: float
    low: float
    high: float


def interval(gains: list[float]) -> Interval:
    n = len(gains)
    mean = sum(gains) / n
    if n < 2:
        return Interval(n, mean, 0.0, float("-inf"), float("inf"))
    sd = math.sqrt(sum((g - mean) ** 2 for g in gains) / (n - 1))
    half = t_crit(n - 1) * sd / math.sqrt(n)
    return Interval(n, mean, sd, mean - half, mean + half)


# Outcome names double as the action the module gets.
ON_BY_DEFAULT = "on_by_default"
OPT_IN = "opt_in"
REMOVE = "remove"
NEEDS_MORE_PAIRS = "needs_more_pairs"
NEEDS_DIFFERENTIAL = "needs_differential"


def verdict(gains: list[float], differential: str) -> tuple[str, str]:
    """Apply the rule in BENCHMARKS.md. Returns (outcome, reason)."""
    if differential == "failed":
        return REMOVE, "the differential scenario test found a behaviour difference"
    if differential != "passed":
        return NEEDS_DIFFERENTIAL, "run scripts/scenario-diff.sh first; a speedup means nothing if behaviour differs"
    if len(gains) < MIN_PAIRS:
        return NEEDS_MORE_PAIRS, f"{len(gains)} pairs; at least {MIN_PAIRS} are required"
    ci = interval(gains)
    if ci.high < 0:
        return REMOVE, f"significant regression: {ci.mean:+.2f}% (95% CI {ci.low:+.2f}..{ci.high:+.2f})"
    if ci.mean >= THRESHOLD_PERCENT and ci.low > 0:
        return ON_BY_DEFAULT, f"{ci.mean:+.2f}% (95% CI {ci.low:+.2f}..{ci.high:+.2f}) clears {THRESHOLD_PERCENT}%"
    return OPT_IN, f"{ci.mean:+.2f}% (95% CI {ci.low:+.2f}..{ci.high:+.2f}) does not clear {THRESHOLD_PERCENT}% with confidence"


def self_test() -> None:
    assert abs(gain_percent(10.0, 9.0) - 10.0) < 1e-9
    assert abs(gain_percent(10.0, 11.0) + 10.0) < 1e-9
    assert t_crit(5) == 2.571 and t_crit(7) == 2.365 and t_crit(200) == 1.98

    steady = [4.0, 4.2, 3.8, 4.1, 3.9, 4.0]
    assert verdict(steady, "passed")[0] == ON_BY_DEFAULT, "a steady +4% gain must clear the rule"
    noisy = [8.0, -2.0, 9.0, -1.0, 7.0, 0.0]
    assert verdict(noisy, "passed")[0] == OPT_IN, "a mean above 3% with a CI crossing zero is not enough"
    small = [1.0, 1.2, 0.8, 1.1, 0.9, 1.0]
    assert verdict(small, "passed")[0] == OPT_IN, "a confident gain below 3% stays opt-in"
    worse = [-3.0, -3.2, -2.8, -3.1, -2.9, -3.0]
    assert verdict(worse, "passed")[0] == REMOVE, "a confident regression is removed"
    assert verdict(steady[:5], "passed")[0] == NEEDS_MORE_PAIRS
    assert verdict(steady, "failed")[0] == REMOVE, "a behaviour difference removes the module whatever the speed"
    assert verdict(steady, "unknown")[0] == NEEDS_DIFFERENTIAL
    ci = interval([2.0, 4.0])
    assert ci.n == 2 and abs(ci.mean - 3.0) < 1e-9 and ci.low < 0 < ci.high
    print("bench_stats self-test passed")


if __name__ == "__main__":
    if "--self-test" in sys.argv[1:]:
        self_test()
    else:
        print(__doc__)
