package dev.ultima.cache.state;

import dev.ultima.cache.VanillaClassGuard;
import dev.ultima.mixin.state_property_cache.BlockStateBaseAccessor;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Caches {@code isRedstoneConductor} only for predicates whose observed behavior is constant
 * and does not touch the world. Package name is not evidence. An unreadable or unproven
 * predicate bypasses the cache.
 */
public final class RedstoneConductorPurity {
    private static final Map<BlockBehaviour.StatePredicate, Boolean> DECISIONS =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final BlockPos PROBE_A = BlockPos.ZERO;
    private static final BlockPos PROBE_B = new BlockPos(30_000_000, -64, -30_000_000);
    private static final BlockGetter THROWING = (BlockGetter) java.lang.reflect.Proxy.newProxyInstance(
            BlockGetter.class.getClassLoader(),
            new Class<?>[] {BlockGetter.class},
            (proxy, method, args) -> {
                throw new WorldRead();
            });

    private RedstoneConductorPurity() {
    }

    public static boolean allows(final BlockState state) {
        if (state == null || state.getBlock().hasDynamicShape() || !VanillaClassGuard.isVanillaType(state.getBlock())) {
            return false;
        }
        BlockBehaviour.StatePredicate predicate = read(state);
        if (predicate == null) {
            return false;
        }
        Boolean decision = DECISIONS.get(predicate);
        if (decision != null) {
            return decision;
        }
        List<BlockState> states = state.getBlock().getStateDefinition().getPossibleStates();
        if (states.size() > 64) {
            states = List.of(state);
        }
        boolean constant = isConstant(predicate, states);
        DECISIONS.put(predicate, constant);
        return constant;
    }

    /**
     * Behavioral probe used by regression tests. A predicate is constant only when every probe
     * returns the same boolean and none of them touch the {@link BlockGetter}.
     */
    public static boolean isConstant(final BlockBehaviour.StatePredicate predicate, final List<BlockState> states) {
        if (predicate == null || states == null || states.isEmpty()) {
            return false;
        }
        Boolean seen = null;
        for (BlockState state : states) {
            if (state == null) {
                return false;
            }
            for (BlockPos pos : List.of(PROBE_A, PROBE_B)) {
                try {
                    boolean value = predicate.test(state, THROWING, pos);
                    if (seen == null) {
                        seen = value;
                    } else if (seen.booleanValue() != value) {
                        return false;
                    }
                } catch (Throwable error) {
                    return false;
                }
            }
        }
        return seen != null;
    }

    private static BlockBehaviour.@Nullable StatePredicate read(final BlockState state) {
        if (state instanceof BlockStateBaseAccessor accessor) {
            try {
                return accessor.ultima$redstoneConductor();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static final class WorldRead extends RuntimeException {
        private WorldRead() {
            super(null, null, false, false);
        }
    }
}
