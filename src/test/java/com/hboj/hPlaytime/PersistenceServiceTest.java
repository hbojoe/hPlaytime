package com.hboj.hPlaytime;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistenceServiceTest {
    @TempDir Path folder;

    @Test void readsLegacyYamlDateKeysAndPreservesAdditionalFieldsAcrossAtomicSaves() throws Exception {
        UUID uuid = UUID.randomUUID(); Path data = folder.resolve("data"); Files.createDirectories(data);
        Files.writeString(data.resolve(uuid + ".yml"), "uuid: " + uuid + "\nname: Legacy\nalltime-millis: 99\n"
            + "daily:\n  2026-10-27: 99\nmonthly:\n  2026-10: 99\ncustom-field: kept\n");
        LocalPlaytimeStorage storage = new LocalPlaytimeStorage(data.toFile()); storage.initialize();
        PlayerData loaded = storage.loadPlayers().get(uuid); assertEquals(99, loaded.daily().get("2026-10-27"));
        storage.apply(TestData.add(uuid, 0, 1));
        PlayerData saved = storage.loadPlayers().get(uuid);
        assertEquals(100, saved.alltime()); assertEquals("kept", saved.extra().get("custom-field"));
        try (var files = Files.list(data)) { assertFalse(files.anyMatch(path -> path.toString().endsWith(".tmp"))); }
    }

    @Test void replaysAnAmbiguousCommitWithoutDoubleCountingAndRetainsRecoveryUntilVerified() throws Exception {
        FakeStorage backend = new FakeStorage(); UUID uuid = UUID.randomUUID();
        PersistenceService first = new PersistenceService(folder, settings -> backend);
        first.activate(first.prepare(TestData.settings()));
        var file = new PersistenceService.FileUpdate("events/test.yml", false, Map.of("name", "test"));
        StorageMutation mutation = TestData.add(uuid, 0, 100);
        var batch = new PersistenceService.Batch(List.of(mutation), Map.of(file.path(), file));
        backend.failAfterWrite = true; assertThrows(IOException.class, () -> first.persist(batch));
        assertEquals(100, backend.loadPlayers().get(uuid).alltime()); assertEquals(1, recoveryFiles());
        first.close();
        PersistenceService restarted = new PersistenceService(folder, settings -> backend);
        var recovered = restarted.prepare(TestData.settings()); restarted.activate(recovered);
        assertEquals(100, recovered.players().get(uuid).alltime()); assertEquals(0, recoveryFiles());
        assertEquals("test", YamlFiles.read(folder.resolve(file.path())).get("name")); restarted.close();
    }

    @Test void failedReadbackKeepsReceiptAndCheckpointUntilTheResultIsVerified() throws Exception {
        FakeStorage backend = new FakeStorage(); PersistenceService service = new PersistenceService(folder, ignored -> backend);
        service.activate(service.prepare(TestData.settings())); UUID uuid = UUID.randomUUID();
        var batch = new PersistenceService.Batch(List.of(TestData.add(uuid, 0, 100)), Map.of());
        backend.failReads = true; assertThrows(IOException.class, () -> service.persist(batch));
        assertEquals(1, recoveryFiles());
        backend.failReads = false; service.persist(batch);
        assertEquals(100, backend.loadPlayers().get(uuid).alltime()); assertEquals(0, recoveryFiles()); service.close();
    }

    @Test void resetAndTombstoneWinOverOlderOperationsAndAreIdempotent() throws Exception {
        PersistenceService service = new PersistenceService(folder); service.activate(service.prepare(TestData.settings()));
        UUID uuid = UUID.randomUUID(); StorageMutation old = TestData.add(uuid, 0, 100);
        var file = new PersistenceService.FileUpdate("events/test.yml", false, Map.of("name", "test"));
        service.persist(new PersistenceService.Batch(List.of(old), Map.of(file.path(), file)));
        var deleted = new PersistenceService.FileUpdate(file.path(), true, Map.of());
        var reset = new PersistenceService.Batch(List.of(StorageMutation.reset(uuid, 100)), Map.of(deleted.path(), deleted));
        service.persist(reset); service.persist(reset); service.persist(new PersistenceService.Batch(List.of(old), Map.of()));
        service.close();
        LocalPlaytimeStorage storage = new LocalPlaytimeStorage(folder.resolve("data").toFile()); storage.initialize();
        assertEquals(0, storage.loadPlayers().get(uuid).alltime()); assertTrue(storage.loadPlayers().get(uuid).daily().isEmpty());
        assertFalse(Files.exists(folder.resolve(file.path()))); storage.close();
    }

    @Test void preparedBackendDoesNotReplaceActiveBackendBeforeMainThreadCommit() throws Exception {
        FakeStorage active = new FakeStorage(), candidate = new FakeStorage();
        var next = new java.util.concurrent.atomic.AtomicReference<PlaytimeStorage>(active);
        PersistenceService service = new PersistenceService(folder, ignored -> next.get());
        service.activate(service.prepare(TestData.settings())); next.set(candidate); service.prepare(TestData.settings());
        UUID uuid = UUID.randomUUID();
        service.persist(new PersistenceService.Batch(List.of(TestData.add(uuid, 0, 123)), Map.of()));
        assertEquals(123, active.loadPlayers().get(uuid).alltime()); assertTrue(candidate.loadPlayers().isEmpty());
        service.close(); assertTrue(active.closed); assertTrue(candidate.closed);
    }

    @Test void checkpointsAreDeeplyImmutableAndRejectEscapingFilePaths() throws Exception {
        Map<String, Object> mutable = new HashMap<>(); mutable.put("nested", new HashMap<>(Map.of("millis", 1)));
        var file = new PersistenceService.FileUpdate("../outside.yml", false, mutable); mutable.clear();
        assertEquals(Map.of("millis", 1), file.data().get("nested"));
        assertThrows(UnsupportedOperationException.class, () -> file.data().put("bad", 1));
        PersistenceService service = new PersistenceService(folder); service.activate(service.prepare(TestData.settings()));
        assertThrows(IllegalArgumentException.class, () -> service.persist(new PersistenceService.Batch(List.of(), Map.of(file.path(), file))));
        service.close(); assertFalse(Files.exists(folder.resolve("../outside.yml")));
    }

    @Test void folderOwnershipPreventsAnOldShutdownWorkerRacingANewPluginInstance() throws Exception {
        PersistenceService old = new PersistenceService(folder); old.activate(old.prepare(TestData.settings()));
        PersistenceService next = new PersistenceService(folder);
        assertThrows(java.nio.channels.OverlappingFileLockException.class, () -> next.prepare(TestData.settings()));
        old.close(); next.activate(next.prepare(TestData.settings())); next.close();
    }

    @Test void indexesPreviouslyJoinedVanillaPlayersWithoutCreatingPlaytimeFiles() throws Exception {
        UUID known = UUID.randomUUID(), unknown = UUID.randomUUID(); Path playerdata = folder.resolve("world/playerdata");
        Files.createDirectories(playerdata); Files.write(playerdata.resolve(known + ".dat"), new byte[0]);
        Path usercache = folder.resolve("usercache.json");
        Files.writeString(usercache, "[{\"uuid\":\"" + known + "\",\"name\":\"Known\"},{\"uuid\":\"" + unknown + "\",\"name\":\"Unknown\"}]");
        PersistenceService service = new PersistenceService(folder.resolve("plugin"), List.of(playerdata), usercache);
        var prepared = service.prepare(TestData.settings()); service.activate(prepared);
        assertEquals("Known", prepared.players().get(known).name()); assertFalse(prepared.players().containsKey(unknown));
        assertFalse(Files.exists(folder.resolve("plugin/data/" + known + ".yml"))); service.close();
    }

    @Test void customDateFormatsContainingDotsKeepTheLegacyNestedYamlStructure() throws Exception {
        UUID uuid = UUID.randomUUID();
        var data = Map.<String, Object>of("uuid", uuid.toString(), "name", "Player", "daily",
            Map.of("2026", Map.of("10", Map.of("27", 123L))), "monthly", Map.of("2026", Map.of("10", 123L)));
        PlayerData player = PlayerData.fromYaml(uuid, data); assertEquals(123, player.daily().get("2026.10.27"));
        assertEquals(123, player.monthly().get("2026.10")); Path file = folder.resolve("custom.yml"); YamlFiles.write(file, player.toYaml());
        var raw = YamlFiles.read(file); assertEquals(Map.of("2026", Map.of("10", Map.of("27", 123))), raw.get("daily"));
        assertEquals(player, PlayerData.fromYaml(uuid, raw));
    }

    @Test void failedReloadPreparationClosesCandidateAndRetainsActiveStorage() throws Exception {
        FakeStorage active = new FakeStorage(), failed = new FakeStorage() {
            @Override public void initialize() { throw new IllegalStateException("database unavailable"); }
        };
        var choice = new java.util.concurrent.atomic.AtomicReference<PlaytimeStorage>(active);
        PersistenceService service = new PersistenceService(folder, ignored -> choice.get()); service.activate(service.prepare(TestData.settings()));
        choice.set(failed); assertThrows(IllegalStateException.class, () -> service.prepare(TestData.settings()));
        assertTrue(failed.closed); assertFalse(active.closed); UUID uuid = UUID.randomUUID();
        service.persist(new PersistenceService.Batch(List.of(TestData.add(uuid, 0, 12)), Map.of()));
        assertEquals(12, active.loadPlayers().get(uuid).alltime()); service.close();
    }

    @Test void localPartialRedoPlanIsReplayedBeforeAnotherOperationAndDoesNotDuplicateTime() throws Exception {
        UUID uuid = UUID.randomUUID(); Path data = folder.resolve("data"); Files.createDirectories(data.resolve(".persistence/receipts"));
        StorageMutation mutation = TestData.add(uuid, 0, 100);
        StorageView desired = mutation.project(StorageView.empty());
        Map<String, Object> plan = Map.of("id", mutation.id().toString(), "hash", mutation.hash(),
            "players", Map.of(uuid.toString(), desired.players().get(uuid).toYaml()), "state", LocalPlaytimeStorage.state(desired));
        YamlFiles.write(data.resolve(".persistence/pending.yml"), plan);
        // Simulate a crash after the player write, before its durable receipt.
        YamlFiles.write(data.resolve(uuid + ".yml"), desired.players().get(uuid).toYaml());
        LocalPlaytimeStorage storage = new LocalPlaytimeStorage(data.toFile()); storage.initialize(); storage.apply(mutation);
        assertEquals(100, storage.loadPlayers().get(uuid).alltime()); assertFalse(Files.exists(data.resolve(".persistence/pending.yml")));
    }

    @Test void readAndWriteLimitsAgreeAndOversizedReplacementRetainsPreviousCheckpoint() throws Exception {
        Path file = folder.resolve("checkpoint.yml"); String valid = "value: \"" + "😀".repeat(16) + "\"\n";
        int size = valid.codePointCount(0, valid.length());
        YamlFiles.writeText(file, valid, size);
        assertEquals("😀".repeat(16), YamlFiles.read(file, size).get("value"));
        assertThrows(IOException.class, () -> YamlFiles.writeText(file, valid + " ", size));
        assertEquals(valid, Files.readString(file));
        assertThrows(IOException.class, () -> YamlFiles.read(file, size - 1));
    }

    private long recoveryFiles() throws IOException {
        try (var files = Files.list(folder)) { return files.filter(path -> path.getFileName().toString().startsWith("recovery-")).count(); }
    }

    static class FakeStorage implements PlaytimeStorage {
        StorageView view = StorageView.empty(); final Map<UUID, String> receipts = new HashMap<>();
        boolean failAfterWrite, failReads, closed;
        @Override public void initialize() {}
        @Override public StorageView loadView() throws IOException { if (failReads) throw new IOException("readback unavailable"); return view; }
        @Override public void apply(StorageMutation mutation) throws IOException {
            String prior = receipts.get(mutation.id());
            if (prior != null) { if (!prior.equals(mutation.hash())) throw new IOException("payload mismatch"); return; }
            view = mutation.project(view); receipts.put(mutation.id(), mutation.hash());
            if (failAfterWrite) { failAfterWrite = false; throw new IOException("lost commit acknowledgement"); }
        }
        @Override public void close() { closed = true; }
    }
}
