# Ultima differential audit harnesses

Run with `bash tools/audit/run.sh`.

These harnesses exist because Ultima's two riskiest modules reorder or replace a vanilla traversal.
A build that succeeds proves the Mixins resolved; it proves nothing about whether the replacement
emits the same sequence as the code it replaced. Each harness puts a transcription of the Ultima
Mixin's logic next to a reference port of the vanilla algorithm and compares them exhaustively.

- `CursorAudit` covers `cursor_step` and the interior-only traversal that `collision_shell_skip`
  drives. It checks that the divide-free traversal emits an identical `(x, y, z, faceType)`
  sequence, that the interior traversal emits exactly the `TYPE_INSIDE` subsequence in order, and
  that both stay exhausted afterwards. It also prints the shell share by collider size, which is
  the quantity `collision_shell_skip`'s benefit is proportional to.
- `SectionAudit` covers `entity_section_lookup`. It checks visited-set and visit-order equivalence
  against a port of the vanilla `sectionIds` tree scan, and then measures the work each side
  performs so the module's fallback guard can be checked against something other than intuition.

## What these are not

They are **reference ports for differential testing only**. They are not part of the mod, are not on
the Gradle build path, and nothing in `src/` depends on them. The vanilla logic they contain is
transcribed to serve as a comparison oracle, in the same spirit as a test fixture.

Because they are transcriptions rather than the real classes, they verify that Ultima's algorithm
agrees with the vanilla algorithm *as documented here*. They cannot verify that this transcription
still matches Minecraft after a version bump. On every Minecraft update, re-read the two vanilla
methods and update the reference ports before trusting the results:

- `net.minecraft.core.Cursor3D#advance` and `#getNextType`
- `net.minecraft.world.level.entity.EntitySectionStorage#forEachAccessibleNonEmptySection`
- `net.minecraft.core.SectionPos#asLong` (the key bit layout the ordering argument rests on)
