package dev.ultima.temporal;

/**
 * Why temporal history must not be reused. Feeding invalid history into a
 * future DLSS/FSR backend is a visual corruption bug, not a performance trade.
 */
public enum TemporalResetReason {
    FIRST_FRAME,
    WORLD_LOAD,
    WORLD_UNLOAD,
    DIMENSION_CHANGE,
    CAMERA_CUT,
    FOV_DISCONTINUITY,
    FRAMEBUFFER_RESIZE,
    RENDER_SCALE_CHANGE,
    GRAPHICS_API_SWITCH,
    RESOURCE_RELOAD,
    RENDERER_REINIT,
    BACKEND_CHANGE
}
