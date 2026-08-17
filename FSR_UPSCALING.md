# Ultima FSR1 spatial upscaling

Optional OpenGL-compatible **FSR1** (EASU + RCAS) post-process. It is a new
module, isolated from retained-terrain and mesher code. **Default off.**

This is **not** a hardware performance claim. The code is implemented, compiles,
and has GPU-free unit tests. It is ready for a first hardware visual/FPS test.

License text and the pinned AMD source are in
[`THIRD_PARTY_LICENSES/AMD_FSR1_LICENSE.md`](THIRD_PARTY_LICENSES/AMD_FSR1_LICENSE.md).

---

## What is implemented

- **FSR1 EASU** (Edge Adaptive Spatial Upsampling) and **FSR1 RCAS** (Robust
  Contrast Adaptive Sharpening) as two fullscreen fragment passes.
- Official constant setup on the CPU: `FsrEasuCon` / `FsrRcasCon`.
- Internal world color+depth target at `native * scale`.
- Native-resolution HUD/GUI/chat/menus drawn **after** upscale, onto vanilla
  `GameRenderer.mainRenderTarget` (never resized away from the window).
- Quality presets and RCAS sharpness in `config/ultima.properties`.
- Fail-open: a compile or evaluate exception disables FSR for the session and
  leaves vanilla native rendering.

Pinned algorithm source: AMD FidelityFX FSR **1.0.2**, header `ffx_fsr1.h`
**v1.20210629**, commit `a21ffb8f6c13233ba336352bdff293894c706575`
([GPUOpen-Effects/FidelityFX-FSR](https://github.com/GPUOpen-Effects/FidelityFX-FSR)).
The GLSL is a HLSL/portable-header → GLSL 330 port. It is **not** copied from
Sodium, Iris, or any other Minecraft mod.

---

## Explicit non-scope

| Not implemented | Why |
|---|---|
| FSR2 / FSR3 temporal upscaling | Needs per-object motion vectors, jitter, and frame history. Ultima's `temporal` module is Native passthrough only. |
| Frame generation / optical flow | Separate feature by project policy; not mixed into native render. |
| DLSS | Closed NVIDIA binary, no official OpenGL path, not distributable with the mod. |
| Temporal jitter / MV textures | FSR1 is spatial and does not use them. |

`TemporalMode.FSR_*` remains **unsupported**. This module does not become a
`TemporalBackend`.

---

## Pipeline integration (Minecraft 26.2)

Vanilla `GameRenderer.render` (simplified):

```text
maybe resize main RT to window (native)
clear main RT
update Globals(ScreenSize = window)
if level:
    renderLevel()          # world, hand, screen-space 3D
    doEntityOutline()
    potion post-chain
FogRenderer.endFrame()
clear depth
guiRenderer.render()       # HUD / screens / chat
```

Ultima, when `fsr_upscaling` is actually enabled:

```text
1. Plan internal size = round(native * presetScale), clamped to [1, native]
2. Allocate Ultima world RT (internal, color+depth) and EASU RT (native, color)
   only if internal != native. Zero extra targets when the module is off.
3. World pass flag ON. GameRenderer.mainRenderTarget() and GETFIELD uses in
   render/renderLevel resolve to the Ultima world RT.
4. LevelRenderer framegraph, hand, screen effects, entity outline, potion
   post-chain all see the internal target / size.
5. Globals.ScreenSize is refreshed to the internal size for that pass.
6. After the world block, at FogRenderer.endFrame:
      EASU  world(internal) → easu(native)
      RCAS  easu(native)    → vanilla main(native)
   World pass flag OFF.
7. Globals.ScreenSize restored to native.
8. HUD/GUI bind vanilla main at native resolution and are not upscaled.
```

### How HUD-after-upscale is guaranteed without a GPU

`GuiRenderer.render()` runs after `FogRenderer.endFrame()` in
`GameRenderer.render`. The FSR evaluate inject is on that `endFrame` call, and
`worldPass` is cleared in `finally` before GUI. GUI therefore samples the
vanilla main target, which FSR just wrote at native size. The start-of-frame
window-size check also keeps vanilla main equal to the window, so FSR never
permanently shrinks the HUD framebuffer.

### Why vanilla main is not resized every frame

`RenderTarget.resize` destroys and reallocates textures. Toggling main between
internal and native every frame would be a per-frame allocation. Ultima instead
owns a persistent internal world target and only redirects reads of
`mainRenderTarget` during the world pass.

---

## Quality presets

| Preset | AMD display scale | Internal scale (each axis) |
|---|---|---|
| Ultra Quality | 1.3× | 1/1.3 ≈ 0.769 |
| Quality (default when the module is on) | 1.5× | 1/1.5 ≈ 0.667 |
| Balanced | 1.7× | 1/1.7 ≈ 0.588 |
| Performance | 2.0× | 0.5 |
| Ultra Performance | 3.0× | 1/3 ≈ 0.333 |

Integer size: `round(native * scale)`, then clamp to `[1, native]`.

`config/ultima.properties`:

```properties
fsr_upscaling=false
fsr_upscaling.preset=quality
fsr_upscaling.sharpness=0.2
```

Optional JVM overrides: `-Dultima.fsr.preset=balanced` and
`-Dultima.fsr.sharpness=0.2`. Sharpness is RCAS stops (`0` = maximum).

---

## Compatibility

Capability-based, not “any renderer mod → off”:

| Loaded mods | `fsr_upscaling` |
|---|---|
| None | Allowed if requested |
| Sodium only | **Allowed.** Sodium replaces terrain meshing/submit. In 26.2 vanilla, the post-world output stage Ultima hooks is still `GameRenderer` / `mainRenderTarget`. Residual risk: a future Sodium that presents into a private output RT. |
| Iris (with or without Sodium) | **Auto-off.** Iris owns the shader / post-process / framebuffer pipeline. Reason: `incompatible_mod` / `iris`. |
| Canvas | **Auto-off.** Canvas owns the renderer. Reason: `incompatible_mod` / `canvas`. |

Independent of `retained_terrain` and mesher modules. Either can be on or off.

---

## Agent decisions

1. **Standalone module, not a TemporalBackend.** FSR1 is spatial. Reusing the
   temporal contract would imply history/jitter that this pass does not use, and
   `temporal` already auto-disables on Sodium.
2. **Redirect `mainRenderTarget` during the world pass** instead of resizing
   vanilla main every frame (see above).
3. **Upscale after outline + potion post, before GUI.** Those passes are part of
   the 3D composite. HUD must stay native; outlines should not be a native-res
   blit onto an internal color buffer.
4. **Hand and first-person screen effects stay on the internal target** because
   they run inside `renderLevel` while the world-pass redirect is active.
5. **GLSL 330 + clamped `texelFetch` gather emulation** so the shader matches
   existing Ultima/vanilla `#version 330` and clamps edges (`CLAMP_TO_EDGE`).
6. **Exact AMD display scales** 1.3 / 1.5 / 1.7 / 2.0 / 3.0 rather than the
   rounded 0.77 / 0.67 / 0.59 table values.

---

## Hardware stage (not done here)

There is no GPU in this environment. Still required on real hardware:

- Visual quality of EASU+RCAS vs native and vs bilinear
- Whether HUD/hotbar/chat/debug text stay sharp
- Edge-of-screen artifacts, gather clamp, fabulous/transparency
- Entity outline scale after the companion-target resize
- Real FPS / 1% low vs native at each preset (do not invent numbers)
- OpenGL shader compile of the Ultima FSR pipelines on the target driver
- Sodium-only smoke (allowed by policy; still untested)
- Iris/Canvas auto-disable log lines in a real loader
- Window resize / fullscreen toggle without stale targets
- Resource-pack reload (pipelines invalidate and recompile)

**SAFE TO MERGE: NO** until that hardware pass exists.
