package dev.ultima.cache.tags;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import org.jspecify.annotations.Nullable;

/**
 * Immutable snapshot: {@code TagKey -> bitset of registry raw ids}.
 *
 * <p>A probe returns {@code Boolean} when the tag was present in this snapshot and the raw id is
 * in range. {@code null} means "this snapshot does not cover the query" and the caller must run
 * vanilla {@code Holder.is(TagKey)}.
 *
 * <p>Missing tags are <em>not</em> answered as false here. A TagKey created after this snapshot
 * was built (or a registry we did not index) must fall back so we never invent membership.
 */
public final class TagBitsetIndex {
    private final int generation;
    private final Map<TagKey<?>, long[]> bitsByTag;
    private final Map<ResourceKey<?>, Integer> bitLengthByRegistry;

    TagBitsetIndex(
            final int generation,
            final Map<TagKey<?>, long[]> bitsByTag,
            final Map<ResourceKey<?>, Integer> bitLengthByRegistry) {
        this.generation = generation;
        this.bitsByTag = Map.copyOf(bitsByTag);
        this.bitLengthByRegistry = Map.copyOf(bitLengthByRegistry);
    }

    public int generation() {
        return this.generation;
    }

    public int tagCount() {
        return this.bitsByTag.size();
    }

    public int coveredRegistryCount() {
        return this.bitLengthByRegistry.size();
    }

    public int bitLength(final ResourceKey<?> registry) {
        Integer length = this.bitLengthByRegistry.get(registry);
        return length == null ? 0 : length;
    }

    public boolean coversRegistry(final ResourceKey<?> registry) {
        return this.bitLengthByRegistry.containsKey(registry);
    }

    public boolean coversTag(final TagKey<?> tag) {
        return this.bitsByTag.containsKey(tag);
    }

    /**
     * @return {@code true}/{@code false} on a covered in-range probe, otherwise {@code null}
     */
    public @Nullable Boolean contains(final TagKey<?> tag, final int rawId) {
        if (tag == null) {
            return null;
        }
        Integer bitLength = this.bitLengthByRegistry.get(tag.registry());
        if (bitLength == null) {
            return null;
        }
        long[] bits = this.bitsByTag.get(tag);
        if (bits == null) {
            return null;
        }
        if (rawId < 0 || rawId >= bitLength) {
            return null;
        }
        int word = rawId >>> 6;
        if (word >= bits.length) {
            return null;
        }
        return (bits[word] & (1L << (rawId & 63))) != 0L;
    }

    public static Builder builder(final int generation) {
        return new Builder(generation);
    }

    public static final class Builder {
        private final int generation;
        private final Map<ResourceKey<?>, Integer> bitLengthByRegistry = new HashMap<>();
        private final Map<TagKey<?>, long[]> bitsByTag = new HashMap<>();

        Builder(final int generation) {
            this.generation = generation;
        }

        public Builder registry(final ResourceKey<?> registry, final int bitLength) {
            Objects.requireNonNull(registry, "registry");
            if (bitLength < 0) {
                throw new IllegalArgumentException("bitLength < 0");
            }
            this.bitLengthByRegistry.put(registry, bitLength);
            return this;
        }

        public Builder emptyTag(final TagKey<?> tag) {
            Objects.requireNonNull(tag, "tag");
            Integer bitLength = this.bitLengthByRegistry.get(tag.registry());
            if (bitLength == null) {
                throw new IllegalStateException("registry not declared for " + tag);
            }
            this.bitsByTag.computeIfAbsent(tag, key -> new long[wordsFor(bitLength)]);
            return this;
        }

        public Builder member(final TagKey<?> tag, final int rawId) {
            Objects.requireNonNull(tag, "tag");
            Integer bitLength = this.bitLengthByRegistry.get(tag.registry());
            if (bitLength == null) {
                throw new IllegalStateException("registry not declared for " + tag);
            }
            if (rawId < 0 || rawId >= bitLength) {
                throw new IllegalArgumentException("rawId " + rawId + " outside [0, " + bitLength + ")");
            }
            long[] bits = this.bitsByTag.computeIfAbsent(tag, key -> new long[wordsFor(bitLength)]);
            bits[rawId >>> 6] |= 1L << (rawId & 63);
            return this;
        }

        public TagBitsetIndex build() {
            Map<TagKey<?>, long[]> frozenBits = new HashMap<>();
            for (Map.Entry<TagKey<?>, long[]> entry : this.bitsByTag.entrySet()) {
                frozenBits.put(entry.getKey(), entry.getValue().clone());
            }
            return new TagBitsetIndex(this.generation, frozenBits, this.bitLengthByRegistry);
        }

        private static int wordsFor(final int bitLength) {
            return bitLength == 0 ? 0 : ((bitLength - 1) >>> 6) + 1;
        }
    }
}
