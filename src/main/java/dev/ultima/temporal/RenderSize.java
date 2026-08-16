package dev.ultima.temporal;

/**
 * Integer framebuffer size. Width/height are at least 1.
 */
public record RenderSize(int width, int height) {
    public RenderSize {
        if (width < 1) {
            width = 1;
        }
        if (height < 1) {
            height = 1;
        }
    }

    public boolean equalsSize(final int width, final int height) {
        return this.width == width && this.height == height;
    }
}
