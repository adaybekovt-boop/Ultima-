package dev.ultima.temporal;

import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector4f;

/**
 * Clip/NDC motion for temporal reconstruction. Matrices are column-major JOML,
 * matching Minecraft 26.2 ({@code Proj * View * vec4(cameraRelative, 1)}).
 *
 * <p>Camera-relative position is the same space vanilla terrain uses after
 * subtracting camera block pos and adding the sub-block camera offset.
 */
public final class MotionVectorMath {
    private MotionVectorMath() {
    }

    public static void ndc(
            final Matrix4fc view,
            final Matrix4fc projection,
            final float cameraRelativeX,
            final float cameraRelativeY,
            final float cameraRelativeZ,
            final Vector4f clipScratch,
            final Vector2f ndcOut) {
        clipScratch.set(cameraRelativeX, cameraRelativeY, cameraRelativeZ, 1.0F);
        view.transform(clipScratch);
        projection.transform(clipScratch);
        if (clipScratch.w == 0.0F || !Float.isFinite(clipScratch.w)) {
            ndcOut.set(0.0F, 0.0F);
            return;
        }
        ndcOut.set(clipScratch.x / clipScratch.w, clipScratch.y / clipScratch.w);
    }

    /**
     * NDC-space velocity of a <strong>static</strong> world point. Previous world
     * transform is the current world transform; only camera motion contributes.
     *
     * @return {@link MotionVectorSemantic#STATIC_WORLD_CAMERA_ONLY}
     */
    public static MotionVectorSemantic staticWorldVelocityNdc(
            final Matrix4fc currentView,
            final Matrix4fc currentProjection,
            final double currentCameraX,
            final double currentCameraY,
            final double currentCameraZ,
            final Matrix4fc previousView,
            final Matrix4fc previousProjection,
            final double previousCameraX,
            final double previousCameraY,
            final double previousCameraZ,
            final double worldX,
            final double worldY,
            final double worldZ,
            final Vector4f clipScratch,
            final Vector2f currentNdcScratch,
            final Vector2f previousNdcScratch,
            final Vector2f velocityOut) {
        ndc(
                currentView,
                currentProjection,
                (float)(worldX - currentCameraX),
                (float)(worldY - currentCameraY),
                (float)(worldZ - currentCameraZ),
                clipScratch,
                currentNdcScratch);
        ndc(
                previousView,
                previousProjection,
                (float)(worldX - previousCameraX),
                (float)(worldY - previousCameraY),
                (float)(worldZ - previousCameraZ),
                clipScratch,
                previousNdcScratch);
        velocityOut.set(
                currentNdcScratch.x - previousNdcScratch.x,
                currentNdcScratch.y - previousNdcScratch.y);
        return MotionVectorSemantic.STATIC_WORLD_CAMERA_ONLY;
    }

    /**
     * Object-motion NDC velocity. Callers must pass the previous and current
     * world-space positions of the same point; camera-only samples are not a
     * substitute.
     */
    public static MotionVectorSemantic objectVelocityNdc(
            final Matrix4fc currentView,
            final Matrix4fc currentProjection,
            final double currentCameraX,
            final double currentCameraY,
            final double currentCameraZ,
            final Matrix4fc previousView,
            final Matrix4fc previousProjection,
            final double previousCameraX,
            final double previousCameraY,
            final double previousCameraZ,
            final double currentWorldX,
            final double currentWorldY,
            final double currentWorldZ,
            final double previousWorldX,
            final double previousWorldY,
            final double previousWorldZ,
            final Vector4f clipScratch,
            final Vector2f currentNdcScratch,
            final Vector2f previousNdcScratch,
            final Vector2f velocityOut) {
        ndc(
                currentView,
                currentProjection,
                (float)(currentWorldX - currentCameraX),
                (float)(currentWorldY - currentCameraY),
                (float)(currentWorldZ - currentCameraZ),
                clipScratch,
                currentNdcScratch);
        ndc(
                previousView,
                previousProjection,
                (float)(previousWorldX - previousCameraX),
                (float)(previousWorldY - previousCameraY),
                (float)(previousWorldZ - previousCameraZ),
                clipScratch,
                previousNdcScratch);
        velocityOut.set(
                currentNdcScratch.x - previousNdcScratch.x,
                currentNdcScratch.y - previousNdcScratch.y);
        return MotionVectorSemantic.OBJECT_TRANSFORM;
    }

    public static void ndcToPixelVelocity(
            final Vector2f ndcVelocity,
            final int width,
            final int height,
            final Vector2f pixelOut) {
        pixelOut.set(ndcVelocity.x * 0.5F * width, ndcVelocity.y * 0.5F * height);
    }

    public static boolean isZero(final Vector2f velocity, final float epsilon) {
        return Math.abs(velocity.x) <= epsilon && Math.abs(velocity.y) <= epsilon;
    }
}
