# Retained opaque GPU time — hypothesis (not a fix)

Scope: document the known **+1.73% … +2.11%** opaque `gpuTerrainNs`
regression with `retained_terrain=ON` from a previous hardware A/B.
This is **not** a change to retained-terrain code. GPU is not the
current FPS limiter (RTX 3090 util ~19–21% on the 3×3 route).

## What the metric is

`RetainedGpuTimers` writes begin/end timestamps on the retained opaque
pass only (SOLID+CUTOUT). Translucent vanilla, sky, entities, and the
rest of the frame are outside the query. A +2% move here can be real
and still be invisible in FPS while the frame is CPU-bound.

## Leading hypothesis: extra per-vertex work vs vanilla UBO

Vanilla opaque terrain binds a `ChunkSection` UBO **per draw** (origin +
visibility as uniforms). Ultima binds one header UBO for the pass and
fetches origin/visibility from `UltimaSectionTable` in the vertex shader:

```
ivec4 ultima_section_record() {
    return texelFetch(UltimaSectionTable, ultima_section_index());
}
```

`terrain_retained.vsh` then calls that twice per vertex
(`ultima_section_origin()` and `ultima_section_visibility()`), so every
terrain vertex does **two** `isamplerBuffer` texel fetches plus
`gl_BaseInstanceARB`. Vanilla does none of that in the VS; visibility is
a uniform.

On an RTX 3090 this is a small, stable tax: the +1.7–2.1% band matches
a constant per-vertex overhead, not a pathological algorithm.

## Contributing hypotheses (secondary)

1. **Dead indirect commands.** Hidden sections keep `instanceCount=0`
   until bounded compaction. The GPU still walks those records in the
   MDI buffer. Compaction is deferred to the next pre-pass flush, so a
   busy stream-in / hide-show frame can draw a bloated command list.
2. **Fragment shader is not the likely delta.** `terrain_retained.fsh`
   copies vanilla RGSS / nearest sampling and only replaces the fade
   mix with the `flat` `UltimaChunkVisibility` varying. If vanilla
   `core/terrain` already does RGSS, sampling cost is shared. Confirm
   against 26.2 `terrain.fsh` before treating RGSS as the regression.
3. **Timestamp queries** wrap the pass. They should not inflate GPU
   work by ~2%, but a driver that treats the query as a barrier could.
   Unlikely given the query-rotation guard, worth ruling out with a
   timer-off A/B if the VS fetch fix is not enough.
4. **Draw grouping.** Retained groups by uber vertex buffer, not
   camera-front-to-back section order. That can change texture-cache
   behavior. Expected effect is scene-dependent, not a flat +2%.

## Suggested future experiment (do not run in this pass)

- Fold the two `texelFetch`es into one `ivec4` in the VS (trivial,
  zero gameplay change).
- Hardware A/B of that shader vs current retained, same scene, scoring
  `gpuTerrainNs` only.
- Optional: compact `instanceCount=0` more eagerly and compare command
  count vs GPU time.

Do not bypass shader-visible stages or change mesh contents to chase
this number. Compatibility stays above the 2% GPU-time delta.
