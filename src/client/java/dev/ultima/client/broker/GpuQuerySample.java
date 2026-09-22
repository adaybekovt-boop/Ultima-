package dev.ultima.client.broker;

import com.mojang.blaze3d.systems.TimerQuery;
import java.lang.reflect.Method;

/**
 * Reads a GPU timer only after a readiness check when the pinned client exposes one.
 *
 * <p>A missing status method cannot tell an unread query from a real zero. In that case a
 * non-positive {@code get()} is {@code NO_DATA} ({@code -1}), never a healthy 0 ms sample.
 * A positive result is still accepted because a completed query is the only way to observe one.
 */
public final class GpuQuerySample {
    private static final Method STATUS = findStatus();

    private GpuQuerySample() {
    }

    /** Negative means no data. Zero is a real zero. Positive is a real duration. */
    public static long nanosForController(final TimerQuery query) {
        if (query == null) {
            return -1L;
        }
        try {
            Boolean ready = statusReady(query);
            if (ready != null && !ready) {
                return -1L;
            }
            long value = query.get();
            if (ready == null && value <= 0L) {
                return -1L;
            }
            return value < 0L ? -1L : value;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static Boolean statusReady(final TimerQuery query) {
        if (STATUS == null) {
            return null;
        }
        try {
            return interpret(STATUS.invoke(query));
        } catch (Throwable ignored) {
            return Boolean.FALSE;
        }
    }

    private static Boolean interpret(final Object status) {
        if (status == null) {
            return Boolean.FALSE;
        }
        if (status instanceof Boolean ready) {
            return ready;
        }
        if (status instanceof Enum<?> value) {
            String name = value.name();
            if (name.contains("PENDING") || name.contains("WAIT") || name.contains("INVALID")
                    || name.contains("NONE") || name.contains("UNAVAILABLE")) {
                return Boolean.FALSE;
            }
            if (name.contains("READY") || name.contains("AVAILABLE") || name.contains("COMPLETE")
                    || name.contains("SUCCESS") || name.contains("OK")) {
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        }
        return Boolean.FALSE;
    }

    private static Method findStatus() {
        for (String name : new String[] {"getStatus", "status", "isReady", "isCompleted", "available"}) {
            try {
                Method method = TimerQuery.class.getMethod(name);
                if (method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                // Try the next published name.
            }
        }
        return null;
    }
}
