# Ultima — Architectural Performance Audit (Pass 2)

Date: 2026-08-14
Branch: `claude/ultima-arch-audit-pass2-qr4gsn`
Scope requested: second-pass architectural review of existing optimization modules.

---

## 0. Premise correction (read this first)

This audit was commissioned as a **second pass** over optimization work performed by a
previous agent, naming four modules to re-examine (`entity_section_lookup`,
`block_collision_shape`, `cursor_step`, `collision_shell_skip`) and three documents to
read (`PERFORMANCE_REPORT.md`, benchmark scripts, `.agent/vanilla-src`).

**None of those artifacts exist, and none has ever existed in this repository.**

This is not a case of work living on another branch or being reverted. It was verified
against the complete object history:

```
git log --all --pretty=format: --name-only --diff-filter=A | sort -u
```

returns exactly 13 files — the workspace skeleton and nothing else. `git log --all -S`
for the module names returns no commits. `main` and this branch are identical
(`git diff --stat main..HEAD` is empty). There are 13 commits total, all authored during
initial workspace setup, none touching game behavior.

The entire mod source is one file:

- `src/main/java/dev/ultima/Ultima.java` — a `ModInitializer` whose `onInitialize()`
  logs one line. It imports no Minecraft classes.

There are **zero** Mixins (the word appears only in prose inside `AGENTS.md` and
`OPTIMIZATION_GUARDRAILS.md`), **zero** tests, **zero** benchmark harnesses, and no
`PERFORMANCE_REPORT.md`.

Consequently the seven audit questions cannot be answered as posed — there are no
bottlenecks that were chosen, no symptom-vs-root-cause tradeoffs that were made, and no
patches to simplify. Sections 2–4 below answer them against what actually exists. The
questions themselves are good; they are simply being asked one stage too early.

I have not invented findings to fill the requested shape. Fabricating a review of
four modules that do not exist would be the most damaging possible outcome of this
session.

---

## 1. Environment verification (what can and cannot be proven here)

The audit brief asks for build, test, and benchmark verification. That is **not
possible in this container**, and the reasons are structural rather than transient:

| Requirement (`AGENTS.md` / `gradle.properties`) | This environment | Status |
|---|---|---|
| Java 25 (`options.release = 25`) | OpenJDK 21.0.10 | **Blocked** |
| Gradle 9.5.1 (via wrapper) | Gradle 8.14.3, no wrapper present | **Blocked** |
| `maven.fabricmc.net` (Loom, Loader, Fabric API) | HTTP 403 at egress proxy | **Blocked** |
| `libraries.minecraft.net` (Mojang) | HTTP 403 at egress proxy | **Blocked** |
| Maven Central | HTTP 200 | OK |
| Gradle Plugin Portal | HTTP 200, but only mirrors `fabric-loom` 0.3.1 — not 1.17-SNAPSHOT | Insufficient |
| `.agent/vanilla-src` | absent; `genSources` needs the two blocked hosts | **Blocked** |

`gradle/wrapper/gradle-wrapper.jar` and `gradlew` are absent, and
`scripts/ensure-wrapper.sh` cannot regenerate them here: `gradle wrapper` configures the
root project, which fails at the Loom `plugins` block (observed — see H6 and §6).

**Implication for the whole project:** no agent working in this network profile can
compile a single line against Minecraft 26.2, read one vanilla method body, or produce
one measurement. Any performance claim generated under these conditions would be
fabricated. This constraint deserves to be treated as the project's top-priority
architectural problem, because it silently converts every optimization task into a
creative-writing task. See Finding **N1**.

---

## 2. Audit of the four named modules

| Module | Verdict | Basis |
|---|---|---|
| `entity_section_lookup` | **NOT PRESENT** | No source, no history, no reference |
| `block_collision_shape` | **NOT PRESENT** | No source, no history, no reference |
| `cursor_step` | **NOT PRESENT** | No source, no history, no reference |
| `collision_shell_skip` | **NOT PRESENT** | No source, no history, no reference |
| "newer modules present in the branch" | **NONE** | Branch is byte-identical to `main` |

Per-module verification of root cause, frequency, semantic equivalence, ordering and
lifecycle assumptions, mod-interaction surface, shader implications, worst-case
behavior, and benchmark representativeness is **not applicable** — there is no code to
which those properties could attach.

If these modules exist somewhere, they are in an environment this session cannot see
(a local Codespace working tree, or an unpushed branch). Nothing was pushed to
`origin`. If that is the case, the work is at risk: an unpushed Codespace tree is
reclaimed when the container is.

---

## 3. Audit of what does exist: the harness

The workspace is the only reviewable artifact, so it is what I reviewed. Three defects
are certain by inspection against the project's own documented contract.

### H1 — `scripts/check.sh` is mandated but missing (certain)

`AGENTS.md` §Verification, step 1: *"Run `bash scripts/check.sh`."* Step 5 of the same
section and the "Evidence required" block in `OPTIMIZATION_GUARDRAILS.md` both treat it
as the gate that makes an optimization retainable. `README.md` lists it under Commands.

The file does not exist. Every agent following the contract hits `No such file or
directory` at its verification step, and the documented failure mode is to be unable to
verify — which in practice becomes verifying nothing and reporting success anyway.
This is the most likely mechanism by which a previous session could have produced
confident module names and benchmark language with no committed code behind them.

**Fixed in this pass.** See §6.

### H2 — `build.gradle` declares no `mappings` dependency (near-certain)

```groovy
dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${project.loader_version}"
    implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"
}
```

Fabric Loom requires an entry in the `mappings` configuration to produce a remapped
Minecraft artifact. With none, Loom cannot resolve a mapping namespace and the build
fails before compiling anything. Every official Fabric template ships either a `yarn`
coordinate or `loom.officialMojangMappings()`.

**Fixed in this pass, with a caveat** — see §6 and the honesty note there.

### H3 — Fabric dependencies use `implementation`, not `modImplementation` (near-certain)

`modImplementation` is what routes an artifact through Loom's remapping and marks it as
a mod for the dev runtime. Declared as plain `implementation`, Fabric Loader and Fabric
API land on the compile classpath in their unremapped (intermediary) namespace, so they
will not link against remapped Minecraft, and Fabric API will not be loaded by
`runClient`/`runServer`. `fabric.mod.json` declares `"fabric-api": "*"` as a hard
dependency, so the dev runtime would refuse to start.

**Fixed in this pass.**

### H6 — `ensure-wrapper.sh` has a bootstrap circularity (certain, observed)

Discovered by running the new `scripts/check.sh` (full transcript in §6). The script
falls back to `gradle wrapper --gradle-version 9.5.1` when no wrapper is present — but
Gradle's `wrapper` task **configures the root project first**, which evaluates the
`plugins { id 'net.fabricmc.fabric-loom' version '1.17-SNAPSHOT' }` block. So
generating the wrapper requires resolving Loom, and the observed failure is:

```
Plugin [id: 'net.fabricmc.fabric-loom', version: '1.17-SNAPSHOT'] was not found
```

No `gradlew` and no `gradle/wrapper/` were produced. The bootstrap path cannot recover
a missing wrapper in any environment where plugin resolution fails — precisely the
environments where you most need a diagnostic.

This is masked in the Codespace (network works, Loom resolves, wrapper generates), so
it is not urgent. It is recorded because it makes every failure mode in a restricted
environment present as the same opaque plugin error, whatever the real cause. A
hardened version would generate the wrapper in a scratch directory with no build script
and copy it in, decoupling wrapper bootstrap from plugin resolution. Not applied —
it is a robustness change with no effect on the working path, and it should not ride
along in an audit commit.

### H4 — documentation claims outrun reality (certain, not fixed)

`README.md` advertises a "GitHub Actions build check". There is no `.github/`
directory. A contributor reasonably infers that pushes are validated by CI; nothing
validates anything. Left unfixed deliberately: adding CI is beyond an audit's remit, it
requires network access this environment lacks, and the choice of runner image for
Java 25 + Loom is the maintainer's. Recorded so the claim is not mistaken for a
working safety net.

### H5 — `repositories {}` is empty, and that is correct (no action)

Noted only to close the loop: Loom's repository plugin injects the Fabric, Mojang, and
Maven Central repositories itself. The empty block matches the official template. This
is **not** a defect.

---

## 4. The seven questions, answered against actual evidence

**1. Did the previous agent optimize the right bottlenecks?**
No optimization was committed, so no bottleneck was addressed, correctly or otherwise.

**2. Are there high-leverage MESO/MACRO opportunities missed?**
Unanswerable with integrity today. Identifying macro-level waste in Minecraft 26.2
requires reading 26.2's actual call graphs. `.agent/vanilla-src` cannot be generated
here (§1). Structural priors from published optimization mods are listed in §5 as a
*research agenda*, explicitly not as findings, and none is implementable now.

**3. Are current optimizations solving symptoms instead of root causes?**
There are no current optimizations. But the *project* has a symptom-vs-root-cause
problem worth naming: the pressure to produce named modules and benchmark numbers,
combined with a verification gate that cannot run (H1) and a toolchain that cannot
build (§1), selects for plausible-looking output over real output. Fixing the
verification loop is the root cause. Adding more optimization tasks is treating the
symptom.

**4. Duplicated work across subsystems?**
Not assessable — one subsystem exists and it logs a string.

**5. Better invalidation / scheduling / indexing / caching / batching / lifecycle
strategies?** Not assessable without vanilla source. Any answer would be recycled
folklore about older Minecraft versions presented as 26.2 analysis.

**6. Can any current patch be simplified or made safer?**
No patches exist.

**7. Architectural patterns that will become fragile as optimizations are added?**
This one **is** answerable, and it is the audit's most useful output:

- **No verification loop.** (H1, H2, H3, §1.) Optimizations are precisely the class of
  change whose correctness is invisible to inspection. A project that cannot build
  cannot safely accept a single one. This is the binding constraint.
- **No behavioral test surface.** Zero tests. `OPTIMIZATION_GUARDRAILS.md` demands
  proof that "observable behavior remains equivalent," but supplies no mechanism to
  express such a proof. Equivalence will be asserted in prose and never checked. Before
  the first Mixin lands, the repo needs a place where "vanilla path and fast path agree
  on these inputs" is executable.
- **No Mixin infrastructure or ordering policy.** `fabric.mod.json` has no `mixins`
  block. The first optimization will introduce one ad hoc. With several modules
  injecting into adjacent systems (collision, entity lookup, chunk lifecycle all touch
  the same call paths), the absence of an upfront convention — package layout,
  `@Injects` over `@Overwrite`, priority policy, per-module kill switches — is what
  turns module #4 into a debugging problem rather than module #1.
- **No config / kill-switch layer.** The guardrails require optimizations to "fail open
  to vanilla" and "self-disable when a conflict is detected." Nothing implements that.
  Retrofitting per-feature toggles after several modules exist is far more invasive than
  establishing the pattern with the first one.
- **Split source sets declared but unpopulated.** `splitEnvironmentSourceSets()` is
  active and `build.gradle` registers `sourceSets.client`, but `src/client` does not
  exist. The rule "never make dedicated-server classloading depend on client classes"
  is currently enforced only by there being no code. The first render-side optimization
  is where this gets tested, and the directory should exist before then.

---

## 5. Ranked findings

### KEEP
- **`AGENTS.md` and `OPTIMIZATION_GUARDRAILS.md` as written.** Genuinely strong
  documents — the priority ordering (correctness > compatibility > pacing > shader
  interop > raw benchmark), the ban on `@Overwrite` and broad cancellation, the
  fail-open requirement, and the evidence checklist are the right constitution for a
  compatibility-first performance mod. The problem is not the rules; it is that no
  machinery enforces them.
- **`.gitignore` policy on `.agent/` and generated Minecraft sources.** Correct and
  legally important.
- **Empty `repositories {}`** (H5) — correct as-is.

### IMPROVE
1. **H1 — create `scripts/check.sh`.** Applied (§6). Highest leverage change available:
   it makes the contract's verification step executable.
2. **H2/H3 — repair `build.gradle` dependency declarations.** Applied (§6).
   Without these the project cannot build at all, in any environment.
3. **H4 — reconcile `README.md` with reality**, either by adding the CI workflow or by
   dropping the claim. Not applied; maintainer's call.
4. **Establish the Mixin + kill-switch conventions before the first optimization**, not
   after the third. Not applied — this is design work that should be decided
   deliberately, not slipped into an audit commit.
5. **Create `src/client/java`** so the split-source-set boundary is real. Not applied;
   trivial, but pointless until there is client code and a build to prove it.

### NEW HIGH-VALUE CANDIDATE
**N1 — make the project buildable and profileable by an agent.** This is the only
candidate I will rank as high-value, and it is deliberately not a game optimization.

Every optimization task issued against this repository in the current network profile
will fail in the same way: the agent cannot read 26.2 source, cannot compile, cannot
measure, and is asked for measured results. The output will be confident prose. That is
the failure this audit is looking at.

Concretely, one of the following must be true before optimization work can be honest:
- the execution environment allows `maven.fabricmc.net` and `libraries.minecraft.net`
  and provides Java 25 + Gradle 9.5.1 (this is what the Codespaces devcontainer does —
  so **run optimization tasks in the Codespace, not in a network-restricted runner**); or
- `.agent/vanilla-src` is generated once in a permitted environment and made available
  to the agent, with a build that can at minimum compile.

Until then, the correct output of an optimization task is "I could not verify this,"
and the correct project response is to fix the environment rather than lower the
evidence bar.

### RESEARCH AGENDA — not findings, not implementable now
Recorded so this pass leaves a starting point, and explicitly flagged: these are
structural priors drawn from the published Minecraft optimization literature (Lithium,
Sodium, C2ME, FerriteCore and similar). **None has been verified against Minecraft
26.2.** Each needs vanilla-source confirmation and a profile before it may be called a
finding, let alone implemented. Listed roughly by historical leverage:

- Chunk/region-based entity spatial queries — whether 26.2 still scans sections
  redundantly for wide AABB queries.
- Collision: shape-caching and cheap-rejection ordering around block-shape lookups.
- Redundant derived-state recomputation on `BlockState` (the classic FerriteCore /
  Lithium territory: deduplicating identical shape and property tables).
- Chunk lifecycle: ticket/status churn and rebuild fan-out breadth.
- Render-side CPU preparation: per-frame allocation and re-sorting that survives
  unchanged inputs.
- Integrated server/client interaction: work duplicated across the singleplayer
  boundary.

The discipline that matters: pick **one**, prove the waste exists in 26.2 with a
profile, then write the narrowest possible Mixin behind a kill switch, with an
equivalence test. Not six at once.

### REJECT
- **Implementing any game optimization in this session.** Writing a Mixin against
  Minecraft 26.2 signatures I cannot read, cannot compile, and cannot measure would
  produce code that is unverifiable at best and non-compiling at worst, while adding
  the appearance of progress. The brief's own instruction — *"If you find no
  sufficiently safe high-value optimization, do not force one"* — is the correct call
  here, and I am taking it.
- **Creating `PERFORMANCE_REPORT.md`.** The brief says to update it "only with measured
  or strongly evidenced results." There are none: no build, no profile, no benchmark,
  no optimization. Creating the file would mean populating it with invented numbers.
  It is deliberately left uncreated.

---

## 6. Changes applied in this pass

Two changes, both harness repairs, both justified by the project's own documented
contract rather than by any performance hypothesis.

**1. Added `scripts/check.sh`** — the verification entry point `AGENTS.md` requires.
Matches the existing scripts' idiom (`set -euo pipefail`, delegates to
`ensure-wrapper.sh`).

**2. Repaired `build.gradle` dependencies:**

```diff
     minecraft "com.mojang:minecraft:${project.minecraft_version}"
-    implementation "net.fabricmc:fabric-loader:${project.loader_version}"
-    implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"
+    mappings loom.officialMojangMappings()
+    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
+    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"
```

**Honesty note on change 2 — this is unverified.** It could not be compiled here (§1).
It is based on Loom's documented requirements and the official Fabric template, not on
a successful build. Two specific caveats:

- `loom.officialMojangMappings()` selects **Mojang official mappings** over Yarn. I
  chose it because the Yarn build number for 26.2 cannot be discovered from this
  container (`maven.fabricmc.net` is blocked) and inventing a version string would
  guarantee a broken build. `AGENTS.md` does not specify a mapping set, and
  `Ultima.java` references no Minecraft classes, so the migration cost of this choice
  is currently **zero**. If Yarn is preferred, it is a one-line swap —
  `mappings "net.fabricmc:yarn:<build>:v2"` plus a `gradle.properties` entry — and it
  should be made now, while nothing depends on it.
- If Loom 1.17 relaxed the `mappings` requirement, the added line is harmless rather
  than wrong.

**Verification status: build attempted, FAILED before reaching these changes.**

`bash scripts/check.sh` was run in this container. Verbatim result:

```
FAILURE: Build failed with an exception.
* Where:
Build file '/home/user/Ultima-/build.gradle' line: 2
* What went wrong:
Plugin [id: 'net.fabricmc.fabric-loom', version: '1.17-SNAPSHOT'] was not found in any
of the following sources:
- Gradle Core Plugins (plugin is not in 'org.gradle' namespace)
- Included Builds (No included builds contain this plugin)
- Plugin Repositories (could not resolve plugin artifact
  'net.fabricmc.fabric-loom:net.fabricmc.fabric-loom.gradle.plugin:1.17-SNAPSHOT')
  Searched in the following repositories:
    Fabric(https://maven.fabricmc.net/)
    MavenRepo
    Gradle Central Plugin Repository
BUILD FAILED in 14s
```

This failure occurs at **plugin resolution on line 2**, i.e. before the `dependencies`
block is ever evaluated. It therefore confirms §1 (the Fabric maven is unreachable and
Loom 1.17-SNAPSHOT cannot be obtained) and simultaneously demonstrates H6, but it
provides **no evidence either way about the correctness of the `mappings` and
`modImplementation` edits** — those lines were never reached. They remain asserted by
inspection only.

Per `AGENTS.md` ("Never claim runtime testing happened if only compilation/build was
performed" — and here the build did not even configure): nothing about this mod has
been compiled or run. **The first action in a Codespace should be
`bash scripts/check.sh`.** That single command now either validates both changes or
reports exactly what is still wrong.

---

## 7. Recommended next step

Do not issue another optimization task against a network-restricted environment. Run
`bash scripts/bootstrap.sh` followed by `bash scripts/check.sh` in the Codespace, get a
green build and a populated `.agent/vanilla-src`, and only then pick a single item from
the research agenda in §5. One verified optimization is worth more than four named ones.
