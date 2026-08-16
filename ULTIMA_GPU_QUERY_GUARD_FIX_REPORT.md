# Ultima — GPU query rotation guard regression fix

## Result

The lost OpenGL query guard was restored from local commit `927b061` and hardened for pool reuse. The fix was tested on the merged main tree `098910bcf5d4c98d066450b90e977eae79068942` before committing the follow-up change.

The guard is now present in `RetainedGpuTimers` as `completedRotations`: a rotation becomes readable only after both begin and end timestamp writes succeed. `poll()` skips EMPTY/partial rotations, including the first frames and re-enable-after-reset path. A reused rotation is cleared when its new begin timestamp is written. Pool close resets rotation state and allows a clean future pool initialization.

The exact PR-description statement is:

> This restores/re-implements the completedRotations guard that was present in an earlier local branch but missing from the tested merge SHA.

## Verification

- `gradlew --no-daemon test`: PASS.
- `gradlew --no-daemon build`: PASS.
- `Audit stage-1 regression checks`: PASS.
- `Command compaction recovery checks`: PASS.
- Bundled Python benchmark self-test: PASS.
- Short retained ON smoke: 600 sample frames, OpenGL RTX 3090.

Smoke JSON:

`run/foundation-results-2.6-gpu-query-smoke/json/ultima-client-benchmark-gpu_query_guard_smoke_on.json`

Smoke log:

`run/foundation-results-2.6-gpu-query-smoke/profile_on/logs/latest.log`

Smoke results:

| Check | Result |
| --- | --- |
| Query object not found | **0** |
| GL_INVALID_OPERATION | **0** |
| Mixin apply failures | **0** |
| Crash reports | **0** |
| timingPairingBalancedFrames | **600 / 600** |
| gpuTimingSupported | **true** |
| gpuTerrainNsAvg | **628,497 ns** |
| terrainTotalCpuNsAvg | **689,321 ns** |
| retainedActive | **true** |

The smoke is a correctness gate only, not a performance claim. The six-pair chunk-flight A/B must be rerun separately after this guard fix; no A/B or release verdict is inferred from 600 frames.

## Changed files

- `src/client/java/dev/ultima/client/renderer/retained/RetainedGpuTimers.java`
- `src/client/java/dev/ultima/client/renderer/retained/GpuQueryRotationGuard.java`
- `src/test/java/dev/ultima/review/AuditStage1Checks.java`

A1/A2 implementation files were not modified.
