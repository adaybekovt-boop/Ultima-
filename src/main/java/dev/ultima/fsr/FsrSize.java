package dev.ultima.fsr;

/**
 * Integer framebuffer size used by FSR1 planning. Width and height are at least 1.
 */
public record FsrSize(int width, int height) {
    public FsrSize {
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
