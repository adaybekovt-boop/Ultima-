package dev.ultima.sleeping;

/**
 * Detects sleep/wake thrashing: entering sleep and waking again without the hopper
 * doing any vanilla work. That pattern means the sleep condition is wrong or a wake
 * channel is firing continuously, and must not degrade into unexplained polling.
 */
public final class SleepCircuitBreaker {
    public static final int WINDOW_TICKS = 40;
    public static final int MAX_EMPTY_CYCLES = 8;

    private long lastSleepTick = Long.MIN_VALUE;
    private long lastWakeTick = Long.MIN_VALUE;
    private int emptyCyclesInWindow;
    private long windowStartTick = Long.MIN_VALUE;
    private boolean tripped;
    private String tripDetail = "";

    public void onSleep(final long gameTime) {
        this.lastSleepTick = gameTime;
        this.rotateWindow(gameTime);
    }

    public void onWake(final long gameTime, final boolean hadWorkSinceSleep) {
        this.lastWakeTick = gameTime;
        this.rotateWindow(gameTime);
        if (hadWorkSinceSleep) {
            this.emptyCyclesInWindow = 0;
            return;
        }
        if (this.lastSleepTick >= 0L && gameTime - this.lastSleepTick <= 2L) {
            this.emptyCyclesInWindow++;
            if (this.emptyCyclesInWindow >= MAX_EMPTY_CYCLES) {
                this.tripped = true;
                this.tripDetail = "empty sleep/wake cycles="
                        + this.emptyCyclesInWindow
                        + " within "
                        + WINDOW_TICKS
                        + " ticks (lastSleep="
                        + this.lastSleepTick
                        + ", lastWake="
                        + this.lastWakeTick
                        + ")";
            }
        }
    }

    public void onWork() {
        this.emptyCyclesInWindow = 0;
    }

    public boolean isTripped() {
        return this.tripped;
    }

    public String tripDetail() {
        return this.tripDetail;
    }

    public int emptyCyclesInWindow() {
        return this.emptyCyclesInWindow;
    }

    private void rotateWindow(final long gameTime) {
        if (this.windowStartTick == Long.MIN_VALUE || gameTime - this.windowStartTick >= WINDOW_TICKS) {
            this.windowStartTick = gameTime;
            this.emptyCyclesInWindow = 0;
        }
    }
}
