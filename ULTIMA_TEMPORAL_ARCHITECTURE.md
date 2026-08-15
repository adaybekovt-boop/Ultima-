# Ultima temporal architecture

Backend-neutral current/previous-frame contract so DLSS/FSR can be added later without forking the renderer. **Native passthrough is the only implemented backend.** It does not blit, scale, jitter, or change pixels.

DLSS, FSR, XeSS, and Frame Generation **do not count** toward the native +25% FPS KPI.

---

## 1. Ownership

| Object | Owner | Notes |
|---|---|---|
| `TemporalFrameData` | `TemporalPipeline` | One instance, render thread |
| `TemporalBackend` | `TemporalPipeline` | Native today; vendor backends later |
| Color / depth views | Vanilla `RenderTarget` | Borrowed handles; Native must not close or blit them |
| Motion-vector texture | Backend | `null` in Native; do not allocate a fake buffer |
| History / reconstructed output | Backend | Native has none |
| HUD / GUI | Vanilla `GameRenderer` after world pass | Always native resolution |

Terrain code (`retained_terrain`) does not call vendor SDKs. It may set `RetainedSectionRecord.temporalFlags` (`FLAG_STATIC_WORLD_TRANSFORM`) so a future MV pass knows previous world == current world.

---

## 2. Frame data

Captured after bob/hurt/nausea have been multiplied into the world projection (`GameRenderer.renderLevel`), which is the matrix actually bound for the level pass:

- current / previous view (`CameraRenderState.viewRotationMatrix`)
- current / previous projection (post-bob `Matrix4f`)
- current / previous camera position
- current / previous FOV
- jitter (always `0,0` in Native)
- render size and output size (equal in Native)
- color + depth `GpuTextureView` from `mainRenderTarget`
- exposure (`1.0` until an HDR path exists)
- `resetHistory` / `resetThisFrame` / reason
- frame index
- motion-vector plan: `STATIC_WORLD_CAMERA_ONLY` or `UNAVAILABLE`

Evaluate runs after `LevelRenderer.render` and **before** the hand pass and the later GUI pass. Native evaluate is a no-op, so hand and HUD still draw into the same native-resolution target.

---

## 3. Native passthrough

```text
getRecommendedRenderSize(outW, outH) == (outW, outH)
beginFrame: jitter = 0; renderSize = outputSize; no MV texture
evaluate: mark evaluated; do not blit; do not scale
```

Unsupported modes (`DLAA`, `DLSS_*`, `FSR_*`) have `isSupported() == false`. Requesting them via `-Dultima.temporal.mode=` resolves to Native and logs once. No fake graphics-menu entries.

---

## 4. Motion vectors

Static terrain:

```text
previous world transform == current world transform
screen velocity = NDC(currView, currProj, world - currCamera)
                 - NDC(prevView, prevProj, world - prevCamera)
```

Camera motion still produces velocity. **Do not** fill entity/hand/particle vectors from camera-only samples. Those need `OBJECT_TRANSFORM` (previous and current object world positions). That path is specified in `MotionVectorMath.objectVelocityNdc` and is not yet produced at runtime.

On a history reset, previous matrices/camera are copied from current so any sample that still runs is zero.

---

## 5. History reset

| Event | Hook | Reason |
|---|---|---|
| First frame | pipeline | `FIRST_FRAME` |
| World load | `Minecraft.setLevel` (previous null) | `WORLD_LOAD` |
| Dimension change | `Minecraft.setLevel` (previous non-null) | `DIMENSION_CHANGE` |
| Disconnect | `Minecraft.disconnect` | `WORLD_UNLOAD` |
| Framebuffer resize | `GameRenderer.resize` | `FRAMEBUFFER_RESIZE` |
| Resource reload | `ShaderManager.apply` RETURN | `RESOURCE_RELOAD` |
| LevelRenderer close | `LevelRenderer.close` | `RENDERER_REINIT` |
| Camera jump &gt; 32 blocks | `beginWorldFrame` | `CAMERA_CUT` |
| FOV jump &gt; 5° | `beginWorldFrame` | `FOV_DISCONTINUITY` |
| Graphics API name change | `DeviceInfo.backendName` | `GRAPHICS_API_SWITCH` |
| Requested mode name change | pipeline | `BACKEND_CHANGE` |

Reset is conservative and idempotent. Invalid history must never be fed to a future upscaler.

---

## 6. HUD composite (required)

```text
3D world (internal render resolution)
        ↓
TemporalBackend.evaluate   // Native: no-op
        ↓
Hand / screen effects      // still vanilla, same target in Native
        ↓
Depth clear
        ↓
Native-resolution GUI / HUD / text
        ↓
Present
```

When an upscaler exists, evaluate must write a **native-resolution** world color buffer before GUI. GUI must not be upscaled.

---

## 7. Module gate

`temporal` is client-only, **default on** (no visual change), auto-off with Sodium / Iris / Canvas. Independent of `retained_terrain`. Fail-open: exceptions disable the pipeline for the session; vanilla rendering continues.

---

## 8. Not in this build

- DLSS / FSR native bridges
- jittered projection
- motion-vector render target
- internal render scale &lt; output
- Frame Generation
- graphics-menu options for unsupported modes
