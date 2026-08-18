package dev.ultima.cache.tags;

import dev.ultima.Ultima;
import dev.ultima.cache.CacheMetrics;
import dev.ultima.config.UltimaConfig;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import org.jspecify.annotations.Nullable;

/**
 * Live tag-membership snapshot used by {@code Holder.Reference.is(TagKey)}.
 *
 * <h2>Covered registries (26.2)</h2>
 * Blocks, items, fluids, entity types, biomes. Other tag kinds fall through to vanilla.
 *
 * <h2>26.2 dynamic registration</h2>
 * {@code MappedRegistry} rejects writes after {@code freeze()}. Vanilla blocks/items/fluids/
 * entity types therefore cannot grow at runtime. Datapack biomes are replaced on reload; this
 * class rebuilds the biome table from the {@link RegistryAccess} supplied by the reload event.
 * If a raw id is still outside the snapshot (unofficial unfreeze, late registration), that
 * single probe falls back to vanilla {@code is()} instead of guessing.
 *
 * <h2>Invalidation sources</h2>
 * <ol>
 *   <li>{@code MappedRegistry.refreshTagsInHolders} (HEAD) — drop snapshot before holders receive
 *       new tag sets, so worker threads never observe a stale bitset against rebound tags.</li>
 *   <li>{@code ServerLifecycleEvents.START_DATA_PACK_RELOAD} — drop before {@code /reload} swaps
 *       datapack contents.</li>
 *   <li>{@code CommonLifecycleEvents.TAGS_LOADED} — full rebuild after tags are bound (server load,
 *       {@code /reload}, client tag sync, datapack set change).</li>
 *   <li>{@code ServerLifecycleEvents.END_DATA_PACK_RELOAD} (success) — rebuild again; idempotent
 *       with TAGS_LOADED.</li>
 *   <li>Module disabled or rebuild failure — snapshot stays null; every probe is vanilla.</li>
 * </ol>
 */
public final class TagBitsetRuntime {
    public static final CacheMetrics METRICS = new CacheMetrics();

    static final List<ResourceKey<? extends Registry<?>>> COVERED_REGISTRIES = List.of(
            Registries.BLOCK,
            Registries.ITEM,
            Registries.FLUID,
            Registries.ENTITY_TYPE,
            Registries.BIOME);

    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static volatile @Nullable TagBitsetIndex snapshot;

    private TagBitsetRuntime() {
    }

    public static boolean moduleEnabled() {
        return UltimaConfig.get().isEnabled("tag_bitsets");
    }

    public static @Nullable TagBitsetIndex snapshot() {
        return snapshot;
    }

    public static void replaceSnapshotForTest(final @Nullable TagBitsetIndex index) {
        snapshot = index;
        if (index != null) {
            GENERATION.set(index.generation());
        }
    }

    public static int generation() {
        return GENERATION.get();
    }

    public static void drop(final String reason) {
        if (snapshot != null) {
            snapshot = null;
            METRICS.invalidations.incrementAndGet();
            Ultima.LOGGER.debug("Ultima tag bitsets dropped ({})", reason);
        }
    }

    public static void rebuild(final RegistryAccess access, final boolean client) {
        if (!moduleEnabled()) {
            drop("module_disabled");
            return;
        }
        if (access == null) {
            drop("null_registry_access");
            return;
        }
        try {
            int generation = GENERATION.incrementAndGet();
            TagBitsetIndex.Builder builder = TagBitsetIndex.builder(generation);
            int registries = 0;
            for (ResourceKey<? extends Registry<?>> key : COVERED_REGISTRIES) {
                if (rebuildCoveredRegistry(builder, access, key)) {
                    registries++;
                }
            }
            TagBitsetIndex built = builder.build();
            snapshot = built;
            METRICS.rebuilds.incrementAndGet();
            Ultima.LOGGER.info(
                    "Ultima tag bitsets rebuilt (client={}, generation={}, registries={}, tags={})",
                    client,
                    generation,
                    registries,
                    built.tagCount());
        } catch (Throwable t) {
            snapshot = null;
            METRICS.invalidations.incrementAndGet();
            Ultima.LOGGER.warn("Ultima tag bitset rebuild failed; using vanilla Holder.is(TagKey).", t);
        }
    }

    /**
     * @return boxed membership when the snapshot covers this probe; {@code null} to run vanilla
     */
    public static @Nullable Boolean lookup(
            final ResourceKey<? extends Registry<?>> holderRegistry,
            final TagKey<?> tag,
            final int rawId) {
        if (!moduleEnabled() || tag == null || holderRegistry == null) {
            METRICS.fallbacks.incrementAndGet();
            return null;
        }
        TagBitsetIndex current = snapshot;
        if (current == null) {
            METRICS.fallbacks.incrementAndGet();
            return null;
        }
        if (!tag.isFor(holderRegistry)) {
            METRICS.fallbacks.incrementAndGet();
            return null;
        }
        Boolean hit = current.contains(tag, rawId);
        if (hit == null) {
            METRICS.misses.incrementAndGet();
            return null;
        }
        METRICS.hits.incrementAndGet();
        return hit;
    }

    static boolean rebuildCoveredRegistry(
            final TagBitsetIndex.Builder builder,
            final RegistryAccess access,
            final ResourceKey<? extends Registry<?>> key) {
        Optional<? extends Registry<?>> found = lookupRegistry(access, key);
        if (found.isEmpty()) {
            return false;
        }
        fillCapturedRegistry(builder, found.get());
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void fillCapturedRegistry(final TagBitsetIndex.Builder builder, final Registry<?> registry) {
        fillRegistry(builder, (Registry)registry);
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<Registry<T>> lookupRegistry(
            final RegistryAccess access, final ResourceKey<? extends Registry<?>> key) {
        return access.lookup((ResourceKey<? extends Registry<? extends T>>)key);
    }

    private static <T> void fillRegistry(final TagBitsetIndex.Builder builder, final Registry<T> registry) {
        int bitLength = 0;
        for (T value : registry) {
            int id = registry.getId(value);
            if (id >= 0) {
                bitLength = Math.max(bitLength, id + 1);
            }
        }
        builder.registry(registry.key(), bitLength);
        registry.listTags().forEach(named -> indexTag(builder, registry, named));
    }

    private static <T> void indexTag(
            final TagBitsetIndex.Builder builder, final Registry<T> registry, final HolderSet.Named<T> named) {
        if (!named.isBound()) {
            return;
        }
        TagKey<T> tag = named.key();
        builder.emptyTag(tag);
        for (Holder<T> holder : named) {
            if (!holder.isBound()) {
                continue;
            }
            int id = registry.getId(holder.value());
            if (id >= 0) {
                builder.member(tag, id);
            }
        }
    }
}
