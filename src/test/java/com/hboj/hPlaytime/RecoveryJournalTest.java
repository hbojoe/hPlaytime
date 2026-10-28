package com.hboj.hPlaytime;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryJournalTest {
    @TempDir Path folder;

    @Test void blockedDatabaseCannotPreventDurableFinalCheckpointAndOlderAckCannotDeleteIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var backend = new PersistenceServiceTest.FakeStorage() {
            boolean blocked;
            @Override public void apply(StorageMutation mutation) throws IOException {
                if (!blocked) {
                    blocked = true; entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test timeout"); }
                    catch (InterruptedException error) { throw new IOException(error); }
                }
                super.apply(mutation);
            }
        };
        PersistenceService service = new PersistenceService(folder, ignored -> backend);
        var prepared = service.prepare(TestData.settings()); service.activate(prepared);
        OrderedIo worker = new OrderedIo(4); UUID uuid = UUID.randomUUID();
        StorageMutation first = TestData.add(uuid, 0, 100), last = TestData.add(uuid, 100, 300);
        var fileA = new PersistenceService.FileUpdate("events/test.yml", false, Map.of("value", "old"));
        var fileB = new PersistenceService.FileUpdate("events/test.yml", false, Map.of("value", "latest"));
        var firstBatch = new PersistenceService.Batch(List.of(first), Map.of(fileA.path(), fileA));
        var finalBatch = new PersistenceService.Batch(List.of(first, last), Map.of(fileB.path(), fileB));
        try {
            var oldSave = worker.submit(() -> service.persist(firstBatch));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var checkpoint = service.stage(prepared.identity(), finalBatch).get(5, TimeUnit.SECONDS);
            assertNotNull(checkpoint); assertTrue(hasRecovery());
            assertFalse(worker.shutdown(Duration.ofMillis(10)));
            release.countDown(); oldSave.get(5, TimeUnit.SECONDS);
            assertTrue(hasRecovery());
            service.persist(finalBatch, java.util.concurrent.CompletableFuture.completedFuture(checkpoint));
            assertEquals(300, backend.loadPlayers().get(uuid).alltime());
            assertEquals("latest", YamlFiles.read(folder.resolve(fileB.path())).get("value"));
            assertFalse(hasRecovery());
        } finally { release.countDown(); worker.shutdown(Duration.ofSeconds(5)); service.close(); }
    }

    @Test void failedShutdownWriteRemainsReplayableAndReadbackFailureDoesNotAcknowledge() throws Exception {
        var backend = new PersistenceServiceTest.FakeStorage(); PersistenceService service = new PersistenceService(folder, ignored -> backend);
        var prepared = service.prepare(TestData.settings()); service.activate(prepared);
        UUID uuid = UUID.randomUUID(); var batch = new PersistenceService.Batch(List.of(TestData.add(uuid, 0, 100)), Map.of());
        var staged = service.stage(prepared.identity(), batch); staged.get(5, TimeUnit.SECONDS);
        backend.failReads = true;
        assertThrows(IOException.class, () -> service.persist(batch, staged)); assertTrue(hasRecovery());
        service.close(); backend.failReads = false;
        PersistenceService restored = new PersistenceService(folder, ignored -> backend);
        var recovery = restored.prepare(TestData.settings()); restored.activate(recovery);
        assertEquals(100, recovery.players().get(uuid).alltime()); assertFalse(hasRecovery()); restored.close();
    }

    @Test void interruptedDisableDoesNotCancelCheckpointOrDropAcceptedWork() throws Exception {
        OrderedIo worker = new OrderedIo(2); CountDownLatch release = new CountDownLatch(1);
        var task = worker.submit(() -> { release.await(); return 42; });
        try {
            Thread.currentThread().interrupt(); assertThrows(InterruptedException.class, () -> worker.shutdown(Duration.ofSeconds(1)));
            release.countDown(); assertTrue(worker.shutdown(Duration.ofSeconds(5))); assertEquals(42, task.get());
        } finally { Thread.interrupted(); release.countDown(); worker.shutdown(Duration.ofSeconds(5)); }
    }

    private boolean hasRecovery() throws IOException {
        try (var files = Files.list(folder)) { return files.anyMatch(path -> path.getFileName().toString().startsWith("recovery-")); }
    }

    @Test void normalJvmExitPersistsAndReplaysTheFinalSnapshotEvenWithBlockedDatabaseWorker() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        Process child = new ProcessBuilder(javaExecutable.toString(), "-cp", System.getProperty("hplaytime.testClasspath"),
            ShutdownProbe.class.getName(), folder.toString(), uuid.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Shutdown hook did not finish within its bounds");
            assertEquals(0, child.exitValue(), () -> {
                try { return new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
                catch (IOException error) { return error.toString(); }
            });
            assertTrue(hasRecovery());
            var backend = new PersistenceServiceTest.FakeStorage();
            PersistenceService restored = new PersistenceService(folder, ignored -> backend);
            try {
                var recovery = restored.prepare(TestData.settings()); restored.activate(recovery);
                assertEquals(300, recovery.players().get(uuid).alltime()); assertFalse(hasRecovery());
            } finally { restored.close(); }
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    @Test void olderBackendRecoveryCannotOverwriteNewerEventStateAfterAClockCorrection() throws Exception {
        var backendA = new PersistenceServiceTest.FakeStorage(); var backendB = new PersistenceServiceTest.FakeStorage();
        var settingsA = PluginSettings.load(Map.of("storage", Map.of("type", "mysql", "mysql", Map.of("host", "backend-a"))));
        var settingsB = PluginSettings.load(Map.of("storage", Map.of("type", "mysql", "mysql", Map.of("host", "backend-b"))));
        var oldFile = new PersistenceService.FileUpdate("events/test.yml", false, Map.of("value", "old"));
        PersistenceService first = new PersistenceService(folder, ignored -> backendA);
        var preparedA = first.prepare(settingsA); first.activate(preparedA);
        first.stage(preparedA.identity(), new PersistenceService.Batch(List.of(), Map.of(oldFile.path(), oldFile))).get();
        first.close();
        Path recoveryA = folder.resolve("recovery-" + preparedA.identity() + ".yml");
        Map<String, Object> restored = new java.util.HashMap<>(YamlFiles.read(recoveryA));
        restored.put("generation", System.currentTimeMillis() + 1000000); YamlFiles.write(recoveryA, restored);
        PersistenceService second = new PersistenceService(folder, ignored -> backendB);
        var preparedB = second.prepare(settingsB); second.activate(preparedB);
        var newFile = new PersistenceService.FileUpdate(oldFile.path(), false, Map.of("value", "latest"));
        second.persist(new PersistenceService.Batch(List.of(), Map.of(newFile.path(), newFile))); second.close();
        PersistenceService reverted = new PersistenceService(folder, ignored -> backendA);
        try {
            var ready = reverted.prepare(settingsA); reverted.activate(ready);
            assertEquals("latest", YamlFiles.read(folder.resolve(newFile.path())).get("value"));
            assertFalse(Files.exists(recoveryA));
        } finally { reverted.close(); }
    }

    @Test void verifyingAnOlderOperationDoesNotAcknowledgeNewerQueuedRecoveryOperations() throws Exception {
        var backend = new PersistenceServiceTest.FakeStorage(); PersistenceService service = new PersistenceService(folder, ignored -> backend);
        var prepared = service.prepare(TestData.settings()); service.activate(prepared);
        UUID uuid = UUID.randomUUID(); StorageMutation first = TestData.add(uuid, 0, 100), later = TestData.add(uuid, 100, 300);
        var firstBatch = new PersistenceService.Batch(List.of(first), Map.of());
        var allPending = new PersistenceService.Batch(List.of(first, later), Map.of());
        var checkpoint = service.stage(prepared.identity(), allPending);
        service.persist(firstBatch, checkpoint);
        assertEquals(100, backend.loadPlayers().get(uuid).alltime()); assertTrue(hasRecovery());
        service.close();
        PersistenceService restored = new PersistenceService(folder, ignored -> backend);
        try {
            var ready = restored.prepare(TestData.settings()); restored.activate(ready);
            assertEquals(300, ready.players().get(uuid).alltime()); assertFalse(hasRecovery());
        } finally { restored.close(); }
    }
}
