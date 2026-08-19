package dev.ultima.cache;

import dev.ultima.cache.state.StatePropertyRuntime;
import dev.ultima.cache.tags.TagBitsetRuntime;
import dev.ultima.config.UltimaConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Exact invalidation wiring for tag bitsets and state-property caches.
 *
 * @see TagBitsetRuntime
 * @see StatePropertyRuntime
 */
public final class RegistryCacheLifecycle {
    private RegistryCacheLifecycle() {
    }

    public static void register() {
        boolean tags = UltimaConfig.get().isEnabled("tag_bitsets");
        boolean properties = UltimaConfig.get().isEnabled("state_property_cache");
        if (!tags && !properties) {
            return;
        }

        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resourceManager) -> {
            if (UltimaConfig.get().isEnabled("tag_bitsets")) {
                TagBitsetRuntime.drop("start_data_pack_reload");
            }
            if (UltimaConfig.get().isEnabled("state_property_cache")) {
                StatePropertyRuntime.invalidateAll("start_data_pack_reload");
            }
        });

        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
            if (UltimaConfig.get().isEnabled("tag_bitsets")) {
                TagBitsetRuntime.rebuild(registries, client);
            }
            if (UltimaConfig.get().isEnabled("state_property_cache")) {
                StatePropertyRuntime.invalidateAll(client ? "client_tags_loaded" : "server_tags_loaded");
            }
        });

        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (!success) {
                if (UltimaConfig.get().isEnabled("tag_bitsets")) {
                    TagBitsetRuntime.drop("end_data_pack_reload_failed");
                }
                if (UltimaConfig.get().isEnabled("state_property_cache")) {
                    StatePropertyRuntime.invalidateAll("end_data_pack_reload_failed");
                }
                return;
            }
            if (UltimaConfig.get().isEnabled("tag_bitsets")) {
                TagBitsetRuntime.rebuild(server.registryAccess(), false);
            }
            if (UltimaConfig.get().isEnabled("state_property_cache")) {
                StatePropertyRuntime.invalidateAll("end_data_pack_reload");
            }
        });
    }
}
