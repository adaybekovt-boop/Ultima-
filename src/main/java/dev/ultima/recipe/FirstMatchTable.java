package dev.ultima.recipe;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Generation-tagged first-match map used by every recipe_match_cache store.
 *
 * <p>A hit returns the exact value stored from a previous vanilla scan for that key. Overflow
 * clears the table rather than evicting an arbitrary entry that could be mistaken for a live
 * result. {@link #invalidate()} bumps the generation token so stale last-hit state cannot be
 * reused across {@code /reload}.
 *
 * @param <K> lookup key (must include reload generation or live in a table that is cleared on reload)
 * @param <V> first vanilla result for that key
 */
public final class FirstMatchTable<K, V> {
    public static final int DEFAULT_CAPACITY = 4096;

    private final int maxEntries;
    private final Map<K, V> entries;
    private int generation;
    private int lastGeneration = -1;
    private @Nullable K lastKey;
    private @Nullable V lastValue;
    private int hits;
    private int misses;
    private int stores;
    private int invalidations;

    public FirstMatchTable() {
        this(DEFAULT_CAPACITY);
    }

    public FirstMatchTable(final int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.entries = HashMap.newHashMap(Math.min(maxEntries, 256));
    }

    public int generation() {
        return this.generation;
    }

    public void invalidate() {
        this.generation++;
        this.entries.clear();
        this.lastKey = null;
        this.lastValue = null;
        this.lastGeneration = -1;
        this.invalidations++;
    }

    public boolean isEmpty() {
        return this.entries.isEmpty() && this.lastKey == null;
    }

    public int size() {
        return this.entries.size();
    }

    public @Nullable V get(final K key) {
        if (this.lastGeneration == this.generation && Objects.equals(this.lastKey, key)) {
            this.hits++;
            return this.lastValue;
        }
        if (this.entries.isEmpty()) {
            this.misses++;
            return null;
        }
        V value = this.entries.get(key);
        if (value == null && !this.entries.containsKey(key)) {
            this.misses++;
            return null;
        }
        this.lastKey = key;
        this.lastValue = value;
        this.lastGeneration = this.generation;
        this.hits++;
        return value;
    }

    public boolean contains(final K key) {
        if (this.lastGeneration == this.generation && Objects.equals(this.lastKey, key)) {
            return true;
        }
        return this.entries.containsKey(key);
    }

    public void put(final K key, final @Nullable V value) {
        if (this.entries.size() >= this.maxEntries && !this.entries.containsKey(key)) {
            this.entries.clear();
        }
        this.entries.put(key, value);
        this.lastKey = key;
        this.lastValue = value;
        this.lastGeneration = this.generation;
        this.stores++;
    }

    public int hits() {
        return this.hits;
    }

    public int misses() {
        return this.misses;
    }

    public int stores() {
        return this.stores;
    }

    public int invalidations() {
        return this.invalidations;
    }
}
