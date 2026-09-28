package dev.ultima.scenario;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Shared state handed to every scenario. */
final class Run {
    private final MinecraftServer server;
    private final ServerLevel level;
    private final Digest digest;
    private int reloadTick = -1;
    private int currentTick;

    Run(final MinecraftServer server, final ServerLevel level, final Digest digest) {
        this.server = server;
        this.level = level;
        this.digest = digest;
    }

    MinecraftServer server() {
        return this.server;
    }

    ServerLevel level() {
        return this.level;
    }

    Digest digest() {
        return this.digest;
    }

    void advanceTo(final int tick) {
        this.currentTick = tick;
    }

    /** Issues {@code /reload} once for the whole run; every scenario shares the same reload. */
    void requestReloadOnce() {
        if (this.reloadTick < 0) {
            this.server.getCommands().performPrefixedCommand(this.server.createCommandSourceStack(), "reload");
            this.reloadTick = this.currentTick;
        }
    }

    /** @return whether the shared reload was requested at least {@code settleTicks} ticks ago */
    boolean reloadSettled(final int settleTicks) {
        return this.reloadTick >= 0 && this.currentTick - this.reloadTick >= settleTicks;
    }
}
