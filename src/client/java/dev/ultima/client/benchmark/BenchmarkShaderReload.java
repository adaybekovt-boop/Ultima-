package dev.ultima.client.benchmark;

import java.lang.reflect.Method;

/**
 * Asks Iris to reload once inside a measured artifact-cache sample. Both A/B sides call this.
 * A missing Iris install is recorded and does not throw into the frame loop.
 */
public final class BenchmarkShaderReload {
    private static boolean requested;

    private BenchmarkShaderReload() {
    }

    public static void requestOnce() {
        if (requested) {
            return;
        }
        requested = true;
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Method reload = iris.getMethod("reload");
            reload.invoke(null);
            ShaderReloadMetrics.markRequested();
        } catch (Throwable throwable) {
            ShaderReloadMetrics.markUnavailable(throwable.getClass().getSimpleName());
        }
    }
}
