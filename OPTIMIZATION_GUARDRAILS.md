# Ultima Performance Guardrails

These rules are mandatory for optimization work.

## Priority order

1. Correctness and world safety.
2. Compatibility with Fabric and other mods.
3. Stable frame pacing / tick pacing.
4. Shader and renderer interoperability.
5. Raw benchmark improvement.

A faster result that violates a higher priority is a regression.

## Never optimize by

- removing or skipping required game logic;
- changing gameplay/tick semantics to do less work;
- changing save formats or network protocols;
- replacing the engine or renderer wholesale;
- using unsafe world/entity/chunk multithreading;
- assuming a Mixin target has no other injections;
- broadly cancelling vanilla methods when a narrower hook is possible;
- bypassing render stages that shader or rendering mods may observe;
- hiding errors, swallowing exceptions, or disabling validation to reduce cost;
- hard-coding behavior around one GPU, driver, JVM, shader pack, or modpack.

## Preferred optimization classes

- avoid repeated calculation when inputs are unchanged;
- narrow cache invalidation and rebuild scope;
- reduce temporary allocations in hot paths;
- avoid repeated collection traversal and redundant lookups;
- batch compatible work while preserving order guarantees;
- perform cheap rejection before expensive work;
- reduce synchronization/contention without weakening thread-safety;
- reduce unnecessary CPU-to-GPU work without changing render contracts;
- improve data locality and algorithmic complexity when semantics remain identical;
- make optional optimizations self-disabling when a conflict is detected.

## Compatibility standard

Absolute compatibility with every possible mod or shader cannot be proven. Ultima must therefore minimize conflict surface:

- prefer Fabric events/APIs;
- use small, targeted Mixins only when necessary;
- avoid `@Overwrite` unless there is no safe alternative and the user explicitly accepts the risk;
- avoid cancelling whole methods;
- keep injections tolerant of other injections where possible;
- fail open to vanilla behavior when assumptions are not satisfied;
- keep client-only optimizations isolated from server code;
- do not depend on implementation details of unrelated mods.

## Evidence required

For each retained optimization, document:
- original hotspot or redundant work;
- changed code path;
- why observable behavior remains equivalent;
- conflict/shader risk;
- build result;
- benchmark or profiling method that can validate the gain.

If the benefit is speculative and the risk is non-trivial, do not keep the change.
