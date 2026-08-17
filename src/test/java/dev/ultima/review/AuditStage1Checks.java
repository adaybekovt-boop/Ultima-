package dev.ultima.review;

import com.mojang.blaze3d.buffers.Std140Builder;
import dev.ultima.config.UltimaModules;
import dev.ultima.client.renderer.retained.GpuQueryRotationGuard;
import dev.ultima.metrics.TerrainCpuPhases;
import dev.ultima.metrics.TerrainCpuPhases.Phase;
import dev.ultima.retained.CommandPopulation;
import dev.ultima.retained.IndexedCommandOwner;
import dev.ultima.retained.RetainedHeaderLayout;
import dev.ultima.retained.RetainedOpaqueSubmitDecision;
import dev.ultima.retained.RetainedOpaqueSubmitDecision.FaultPoint;
import dev.ultima.retained.SectionSlotIdentity;
import dev.ultima.retained.SubmitCommandList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.DynamicUniforms;
import org.joml.Matrix4f;

/**
 * Pure regression coverage for forensic audit D1–D4 / G1–G5.
 */
final class AuditStage1Checks {
    private AuditStage1Checks() {
    }

    static void run() {
        testFailOpenCancellationDecision();
        testSubmitGroupSwapRemove();
        testViewAreaSlotIdentityReset();
        testVisibilitySequences();
        testCommandGrowthAccounting();
        testTerrainCpuTimingModel();
        testGpuQueryRotationGuard();
        testHeaderLayout();
        testModuleClassification();
        System.out.println("Audit stage-1 regression checks passed.");
    }

    private static void testFailOpenCancellationDecision() {
        assertTrue(
                RetainedOpaqueSubmitDecision.shouldCancelVanillaOpaque(true),
                "successful submit must cancel vanilla opaque");
        assertFalse(
                RetainedOpaqueSubmitDecision.shouldCancelVanillaOpaque(false),
                "failed submit must not cancel vanilla opaque");

        var before = RetainedOpaqueSubmitDecision.simulate(FaultPoint.BEFORE_ANY_DRAW, 3);
        assertFalse(before.completedSuccessfully(), "before-draw fault is a failure");
        assertFalse(before.issuedAnyDraw(), "no group flushed before the fault");
        assertFalse(before.shouldCancelVanillaOpaque(), "vanilla must run after before-draw fault");
        assertTrue(before.failureReason() != null && !before.failureReason().isBlank(), "failure is logged via reason");

        var after = RetainedOpaqueSubmitDecision.simulate(FaultPoint.AFTER_FIRST_GROUP, 3);
        assertFalse(after.completedSuccessfully(), "after-group fault is a failure");
        assertTrue(after.issuedAnyDraw(), "at least one group flushed before the fault");
        assertFalse(after.shouldCancelVanillaOpaque(), "vanilla must run after partial submit");

        var success = RetainedOpaqueSubmitDecision.simulate(FaultPoint.NONE, 2);
        assertTrue(success.completedSuccessfully(), "no fault is success");
        assertTrue(success.issuedAnyDraw(), "live groups issue draws");
        assertTrue(success.shouldCancelVanillaOpaque(), "only full success cancels vanilla");

        var emptySuccess = RetainedOpaqueSubmitDecision.simulate(FaultPoint.NONE, 0);
        assertTrue(emptySuccess.completedSuccessfully(), "zero-group submit can still complete");
        assertFalse(emptySuccess.issuedAnyDraw(), "zero-group submit issued no draws");
        assertTrue(
                emptySuccess.shouldCancelVanillaOpaque(),
                "a completed retained submit with no live groups still owns the opaque pass");
    }

    private static void testSubmitGroupSwapRemove() {
        SubmitCommandList list = new SubmitCommandList();
        FakeOwner keep = new FakeOwner("keep");
        FakeOwner drop = new FakeOwner("drop");
        list.add(drop, 0, 6, 0, 1);
        list.add(keep, 10, 36, 4, 2);
        list.setVisible(keep, false);
        assertEquals(1, list.liveDraws(), "one live after hiding keep");
        assertTrue(list.isDirty(1), "visibility dirty on keep");
        list.dirty().clear();

        list.remove(drop);
        assertEquals(1, list.count(), "swap-remove shrinks the list");
        assertEquals(0, list.liveDraws(), "hidden keep stays hidden");
        assertEquals(0, keep.getCommandIndex(), "moved owner receives the hole index");
        assertEquals(-1, drop.getCommandIndex(), "removed owner index is invalidated");
        assertEquals(10, list.firstIndexAt(0), "immutable fields follow the moved command");
        assertEquals(36, list.indexCountAt(0), "indexCount follows the moved command");
        assertEquals(4, list.baseVertexAt(0), "baseVertex follows the moved command");
        assertEquals(2, list.sectionSlotAt(0), "section slot follows the moved command");
        assertEquals(0, list.instanceCountAt(0), "visibility follows the moved command");
        assertTrue(list.isDirty(0), "GPU hole index is dirty after the move");
        assertFalse(list.isDirty(1), "vacated last index is not dirty");
        assertTrue(keep == list.ownerAt(0), "owner pointer follows the moved command");

        list.remove(keep);
        assertEquals(0, list.count(), "removing the last command empties the group");
        assertEquals(0, list.liveDraws(), "empty group has no live draws");
        assertEquals(-1, keep.getCommandIndex(), "last owner is detached");
    }

    private static void testViewAreaSlotIdentityReset() {
        assertFalse(SectionSlotIdentity.mustReset(10L, 10L), "same node is the same identity");
        assertTrue(SectionSlotIdentity.mustReset(10L, 11L), "different node is a ring wrap");

        SubmitCommandList list = new SubmitCommandList();
        FakeOwner solid = new FakeOwner("solid");
        FakeOwner cutout = new FakeOwner("cutout");
        long storedNode = 100L;
        list.add(solid, 0, 12, 0, 7);
        list.add(cutout, 12, 24, 3, 7);
        assertEquals(2, list.count(), "both layers occupy the old identity");

        assertTrue(SectionSlotIdentity.mustReset(storedNode, 200L), "incoming node is a new identity");
        list.remove(solid);
        list.remove(cutout);
        assertEquals(0, list.count(), "old layer slots are removed");
        assertEquals(-1, solid.getCommandIndex(), "old solid command index is invalidated");
        assertEquals(-1, cutout.getCommandIndex(), "old cutout command index is invalidated");
        assertEquals(0, list.liveDraws(), "new identity starts with no live draws");

        FakeOwner fresh = new FakeOwner("fresh");
        int index = list.add(fresh, 0, 6, 0, 7);
        assertEquals(0, index, "new identity begins at a clean command index");
        assertEquals(1, list.count(), "only the new command exists");
        assertEquals(1, list.liveDraws(), "new identity is visible until hidden");
        assertTrue(list.isDirty(0), "new command is dirty for upload");
    }

    private static void testVisibilitySequences() {
        SubmitCommandList list = new SubmitCommandList();
        FakeOwner slot = new FakeOwner("slot");
        list.add(slot, 0, 36, 0, 3);
        list.resetFrameCounters();

        boolean[] frames = {true, false, true, false};
        for (boolean visible : frames) {
            list.setVisible(slot, visible);
        }
        assertEquals(0, list.liveDraws(), "final frame is hidden");
        assertEquals(1, list.count(), "hidden command is retained");
        assertEquals(1, list.hiddenZeroInstanceCommands(), "one zero-instance command");
        assertEquals(0.0, list.liveToTotalRatio(), 1e-9, "live ratio is 0 when hidden");
        assertEquals(3, list.visibilityToggles(), "true→false→true→false is three toggles");
        assertEquals(0, slot.instanceCount, "owner visibility matches the list");

        list.remove(slot);
        assertEquals(0, list.count(), "removing a hidden command empties the group");
        assertEquals(0, list.liveDraws(), "live count does not go negative");
        assertEquals(1, list.commandsRemoved(), "hidden removal is counted");

        FakeOwner rebuilt = new FakeOwner("rebuilt");
        list.add(rebuilt, 0, 36, 0, 4);
        list.setVisible(rebuilt, false);
        assertEquals(0, rebuilt.instanceCount, "hidden before mesh change");
        assertEquals(0, list.liveDraws(), "hidden slot is not live");
        list.updateImmutable(rebuilt, 8, 48, 2);
        assertEquals(8, list.firstIndexAt(0), "geometry firstIndex updated");
        assertEquals(48, list.indexCountAt(0), "geometry indexCount updated");
        assertEquals(2, list.baseVertexAt(0), "geometry baseVertex updated");
        assertEquals(1, list.instanceCountAt(0), "updateImmutable restores instanceCount");
        assertEquals(1, rebuilt.instanceCount, "owner visibility is restored");
        assertEquals(1, list.liveDraws(), "restored slot is live the same frame");
        list.updateImmutable(rebuilt, 8, 48, 2);
        assertEquals(1, list.instanceCountAt(0), "already-visible updateImmutable stays visible");
        assertEquals(1, list.liveDraws(), "already-visible update does not double-count live");
    }

    private static void testCommandGrowthAccounting() {
        SubmitCommandList list = new SubmitCommandList();
        FakeOwner[] owners = new FakeOwner[20];
        for (int i = 0; i < owners.length; i++) {
            owners[i] = new FakeOwner("g" + i);
            list.add(owners[i], i, 6, i, i);
        }
        assertTrue(list.capacity() >= 20, "CPU array grew");
        assertTrue(list.arrayReallocs() >= 1, "growth is counted");
        assertEquals(20, list.count(), "all commands present");
        assertEquals(20, list.liveDraws(), "all live");
        assertEquals(20, list.commandsAdded(), "adds are counted");

        for (int i = 0; i < 10; i++) {
            list.setVisible(owners[i], false);
        }
        assertEquals(10, list.hiddenZeroInstanceCommands(), "ten hidden");
        assertEquals(10, list.liveDraws(), "ten live");
        double ratio = CommandPopulation.ratio(list.liveDraws(), list.count());
        assertEquals(0.5, ratio, 1e-9, "live/total is 0.5");

        list.remove(owners[19]);
        assertEquals(19, list.count(), "one removed");
        assertEquals(18, owners[18].getCommandIndex(), "last swap target unchanged when removing last");
    }

    private static void testTerrainCpuTimingModel() {
        AtomicLong clock = new AtomicLong(1_000L);
        TerrainCpuPhases cpu = new TerrainCpuPhases(clock::get);

        cpu.begin(Phase.PREPARE);
        clock.addAndGet(100);
        cpu.end(Phase.PREPARE);
        cpu.begin(Phase.OPAQUE_SUBMIT);
        clock.addAndGet(40);
        cpu.end(Phase.OPAQUE_SUBMIT);
        cpu.begin(Phase.TRANSLUCENT_SUBMIT);
        clock.addAndGet(25);
        cpu.end(Phase.TRANSLUCENT_SUBMIT);
        assertTrue(cpu.pairingBalanced(), "vanilla begin/end pairing");
        assertEquals(100, cpu.accumNs(Phase.PREPARE), "vanilla prepare");
        assertEquals(40, cpu.accumNs(Phase.OPAQUE_SUBMIT), "vanilla opaque submit");
        assertEquals(25, cpu.accumNs(Phase.TRANSLUCENT_SUBMIT), "vanilla translucent submit");
        assertEquals(165, cpu.totalCpuNs(), "vanilla total is prepare+opaque+translucent");
        assertEquals(0, cpu.accumNs(Phase.COMMAND), "vanilla has no command bucket");

        cpu.beginFrame();
        assertEquals(0, cpu.totalCpuNs(), "beginFrame drops previous-frame totals");
        assertEquals(0, cpu.accumNs(Phase.PREPARE), "beginFrame drops previous prepare");
        assertTrue(cpu.pairingBalanced(), "fresh frame is balanced");

        cpu.begin(Phase.PREPARE);
        cpu.begin(Phase.COMMAND);
        clock.addAndGet(80);
        cpu.end(Phase.COMMAND);
        clock.addAndGet(20);
        cpu.end(Phase.PREPARE);
        cpu.begin(Phase.OPAQUE_SUBMIT);
        clock.addAndGet(15);
        cpu.end(Phase.OPAQUE_SUBMIT);
        cpu.begin(Phase.TRANSLUCENT_SUBMIT);
        clock.addAndGet(25);
        cpu.end(Phase.TRANSLUCENT_SUBMIT);
        assertTrue(cpu.pairingBalanced(), "retained success pairing");
        assertEquals(100, cpu.accumNs(Phase.PREPARE), "retained prepare includes command generation");
        assertEquals(80, cpu.accumNs(Phase.COMMAND), "command bucket is the inner interval");
        assertEquals(15, cpu.accumNs(Phase.OPAQUE_SUBMIT), "retained opaque submit is measured");
        assertEquals(140, cpu.totalCpuNs(), "total does not double-count command ns");

        cpu.beginFrame();
        cpu.begin(Phase.PREPARE);
        clock.addAndGet(30);
        cpu.end(Phase.PREPARE);
        cpu.begin(Phase.PREPARE);
        clock.addAndGet(50);
        cpu.end(Phase.PREPARE);
        cpu.begin(Phase.OPAQUE_SUBMIT);
        clock.addAndGet(10);
        cpu.end(Phase.OPAQUE_SUBMIT);
        cpu.begin(Phase.OPAQUE_SUBMIT);
        clock.addAndGet(40);
        cpu.end(Phase.OPAQUE_SUBMIT);
        cpu.begin(Phase.TRANSLUCENT_SUBMIT);
        clock.addAndGet(20);
        cpu.end(Phase.TRANSLUCENT_SUBMIT);
        assertTrue(cpu.pairingBalanced(), "fail-open sequential pairing");
        assertEquals(80, cpu.accumNs(Phase.PREPARE), "failed attempt plus vanilla prepare");
        assertEquals(50, cpu.accumNs(Phase.OPAQUE_SUBMIT), "failed attempt plus vanilla opaque");
        assertEquals(150, cpu.totalCpuNs(), "fail-open total includes fallback work");

        cpu.beginFrame();
        cpu.begin(Phase.OPAQUE_SUBMIT);
        cpu.begin(Phase.OPAQUE_SUBMIT);
        clock.addAndGet(12);
        cpu.end(Phase.OPAQUE_SUBMIT);
        clock.addAndGet(8);
        cpu.end(Phase.OPAQUE_SUBMIT);
        assertEquals(20, cpu.accumNs(Phase.OPAQUE_SUBMIT), "nested mixin+retained opaque counts once");
        assertEquals(2, cpu.begins(), "two begins");
        assertEquals(2, cpu.ends(), "two ends");
        assertTrue(cpu.pairingBalanced(), "nested pairing is balanced");

        // ON-side retained cancel: mixin HEAD begins, retained self-times and
        // ends, then cancellable inject returns without the metrics RETURN hook.
        cpu.beginFrame();
        cpu.begin(Phase.PREPARE);
        cpu.begin(Phase.PREPARE);
        cpu.begin(Phase.COMMAND);
        clock.addAndGet(40);
        cpu.end(Phase.COMMAND);
        clock.addAndGet(10);
        cpu.end(Phase.PREPARE);
        assertFalse(cpu.pairingBalanced(), "skipped RETURN leaves PREPARE depth=1");
        assertEquals(0, cpu.accumNs(Phase.PREPARE), "unclosed outer PREPARE does not accumulate");
        cpu.closeOpen(Phase.PREPARE);
        assertTrue(cpu.pairingBalanced(), "closeOpen after cancel balances PREPARE");
        assertEquals(50, cpu.accumNs(Phase.PREPARE), "closeOpen records the outer PREPARE interval");
        assertEquals(0, cpu.depth(Phase.PREPARE), "PREPARE depth returns to 0");
        cpu.closeOpen(Phase.PREPARE);
        assertTrue(cpu.pairingBalanced(), "closeOpen on a closed phase is a no-op");
        assertEquals(50, cpu.accumNs(Phase.PREPARE), "no-op closeOpen does not add time");

        cpu.begin(Phase.OPAQUE_SUBMIT);
        cpu.begin(Phase.OPAQUE_SUBMIT);
        clock.addAndGet(15);
        cpu.end(Phase.OPAQUE_SUBMIT);
        assertFalse(cpu.pairingBalanced(), "skipped RETURN leaves OPAQUE_SUBMIT depth=1");
        assertEquals(0, cpu.accumNs(Phase.OPAQUE_SUBMIT), "unclosed outer OPAQUE_SUBMIT does not accumulate");
        cpu.closeOpen(Phase.OPAQUE_SUBMIT);
        assertTrue(cpu.pairingBalanced(), "closeOpen after cancel balances OPAQUE_SUBMIT");
        assertEquals(15, cpu.accumNs(Phase.OPAQUE_SUBMIT), "closeOpen records the outer OPAQUE_SUBMIT interval");
        assertEquals(65, cpu.totalCpuNs(), "ON-side total is prepare+opaque after cancel close");
        assertEquals(0, cpu.pairingErrors(), "cancel-path close must not count as a pairing error");
    }

    private static void testGpuQueryRotationGuard() {
        boolean[] completed = new boolean[3];
        assertFalse(
                GpuQueryRotationGuard.canRead(completed, 1),
                "an unwritten rotation must be skipped without reading a query object");
        completed[1] = true;
        assertTrue(
                GpuQueryRotationGuard.canRead(completed, 1),
                "only a rotation with a complete begin/end pair may be read");
        completed[1] = false;
        assertFalse(
                GpuQueryRotationGuard.canRead(completed, 1),
                "a rotation becomes unreadable again when its slot is reused");
        assertFalse(
                GpuQueryRotationGuard.canRead(completed, -1),
                "negative rotation cannot address a query pair");
        assertFalse(
                GpuQueryRotationGuard.canRead(completed, completed.length),
                "out-of-range rotation cannot address a query pair");
    }

    private static void testHeaderLayout() {
        int vanillaSize = DynamicUniforms.CHUNK_SECTION_UBO_SIZE;
        assertTrue(vanillaSize >= RetainedHeaderLayout.UNUSED_ORIGIN_OFFSET + 12, "vanilla UBO covers origin field");

        ByteBuffer data = ByteBuffer.allocateDirect(vanillaSize).order(ByteOrder.nativeOrder());
        Matrix4f modelView = new Matrix4f().identity();
        modelView.m00(2.0F);
        modelView.m11(3.0F);
        modelView.m22(4.0F);
        modelView.m33(5.0F);
        Std140Builder.intoBuffer(data)
                .putMat4f(modelView)
                .putFloat(1.0F)
                .putIVec2(16, 32)
                .putIVec3(0, 0, 0);
        int written = data.position();
        assertTrue(written <= vanillaSize, "header write fits vanilla ChunkSection UBO");
        data.clear();
        assertEqualsFloat(2.0F, data.getFloat(RetainedHeaderLayout.MODEL_VIEW_OFFSET), "m00 at model-view origin");
        assertEqualsFloat(1.0F, data.getFloat(RetainedHeaderLayout.UNUSED_FADE_OFFSET), "unused fade at +64");
        assertEquals(16, data.getInt(RetainedHeaderLayout.TEXTURE_SIZE_OFFSET), "atlas width");
        assertEquals(32, data.getInt(RetainedHeaderLayout.TEXTURE_SIZE_OFFSET + 4), "atlas height");
        assertEquals(0, data.getInt(RetainedHeaderLayout.UNUSED_ORIGIN_OFFSET), "unused origin x");
        assertEquals(64, RetainedHeaderLayout.MODEL_VIEW_BYTES, "mat4 is 64 bytes");
        assertEquals(0, RetainedHeaderLayout.MODEL_VIEW_OFFSET, "model-view is at 0");
    }

    private static void testModuleClassification() {
        assertTrue(UltimaModules.kind(UltimaModules.byKey("entity_section_lookup")) == UltimaModules.Kind.SHIPPED_DEFAULT,
                "entity_section_lookup is a shipped default");
        assertTrue(UltimaModules.kind(UltimaModules.byKey("block_collision_shape")) == UltimaModules.Kind.SHIPPED_DEFAULT,
                "block_collision_shape is a shipped default");
        assertTrue(UltimaModules.kind(UltimaModules.byKey("collision_shell_skip")) == UltimaModules.Kind.SHIPPED_DEFAULT,
                "collision_shell_skip is a shipped default");
        assertTrue(UltimaModules.kind(UltimaModules.byKey("cursor_step")) == UltimaModules.Kind.SHIPPED_DEFAULT,
                "cursor_step is a shipped default");
        assertTrue(UltimaModules.kind(UltimaModules.byKey("terrain_metrics")) == UltimaModules.Kind.INSTRUMENTATION,
                "terrain_metrics is instrumentation");
        assertTrue(UltimaModules.kind(UltimaModules.byKey("client_benchmark")) == UltimaModules.Kind.INSTRUMENTATION,
                "client_benchmark is instrumentation");
        assertTrue(UltimaModules.kind(UltimaModules.byKey("retained_terrain")) == UltimaModules.Kind.OPT_IN_EXPERIMENT,
                "retained_terrain is an opt-in experiment");
        assertTrue(UltimaModules.kind(UltimaModules.byKey("temporal")) == UltimaModules.Kind.SHIPPED_DEFAULT,
                "temporal Native passthrough is a shipped client default");
        assertFalse(UltimaModules.isOptInExperiment("entity_section_lookup"), "defaults are not experimental");
        assertTrue(UltimaModules.isOptInExperiment("java_mesher"), "java_mesher remains experimental");
        assertTrue(UltimaModules.kind(UltimaModules.byKey("blockentity_sleeping")) == UltimaModules.Kind.OPT_IN_EXPERIMENT,
                "blockentity_sleeping is an opt-in experiment");
        assertTrue(UltimaModules.isOptInExperiment("blockentity_sleeping"), "blockentity_sleeping remains experimental");
    }

    private static void assertTrue(final boolean value, final String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(final boolean value, final String message) {
        assertTrue(!value, message);
    }

    private static void assertEquals(final long expected, final long actual, final String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(
            final double expected, final double actual, final double epsilon, final String message) {
        if (Math.abs(expected - actual) > epsilon) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEqualsFloat(final float expected, final float actual, final String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static final class FakeOwner implements IndexedCommandOwner {
        private final String name;
        private int commandIndex = -1;
        private int instanceCount;

        private FakeOwner(final String name) {
            this.name = name;
        }

        @Override
        public int getCommandIndex() {
            return this.commandIndex;
        }

        @Override
        public void onCommandAttached(final int commandIndex, final int instanceCount) {
            this.commandIndex = commandIndex;
            this.instanceCount = instanceCount;
        }

        @Override
        public void onCommandMoved(final int newCommandIndex) {
            this.commandIndex = newCommandIndex;
        }

        @Override
        public void onCommandDetached() {
            this.commandIndex = -1;
            this.instanceCount = 0;
        }

        @Override
        public String toString() {
            return this.name + "@" + this.commandIndex;
        }
    }
}
