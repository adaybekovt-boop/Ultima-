package net.irisshaders.iris.gl.blending;

/** Minimal exact-name test fixture for Iris' alpha transformation input. */
public record AlphaTest(Function function, float reference) {
    public enum Function {
        ALWAYS,
        GREATER
    }
}
