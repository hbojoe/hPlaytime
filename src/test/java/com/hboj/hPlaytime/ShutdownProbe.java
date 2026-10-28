package com.hboj.hPlaytime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Child JVM used to test the actual Runtime shutdown hook while the database worker is blocked. */
public final class ShutdownProbe {
    public static void main(String[] args) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), never = new CountDownLatch(1);
        PlaytimeStorage unavailable = new PlaytimeStorage() {
            public void initialize() {}
            public StorageView loadView() { return StorageView.empty(); }
            public void apply(StorageMutation mutation) throws Exception { entered.countDown(); never.await(); }
            public void close() {}
        };
        PersistenceService service = new PersistenceService(Path.of(args[0]), ignored -> unavailable);
        var prepared = service.prepare(TestData.settings()); service.activate(prepared);
        OrderedIo io = new OrderedIo(4); ShutdownGuard guard = new ShutdownGuard(io, Logger.getLogger("shutdown-test")); guard.timeout(5);
        UUID uuid = UUID.fromString(args[1]); StorageMutation first = TestData.add(uuid, 0, 100), second = TestData.add(uuid, 100, 300);
        io.submit(() -> service.persist(new PersistenceService.Batch(List.of(first), Map.of())));
        if (!entered.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Database block was not reached");
        guard.checkpoint(service.stage(prepared.identity(), new PersistenceService.Batch(List.of(first, second), Map.of())));
        // Deliberately do not wait or drain on this thread. The hook must protect the checkpoint.
        System.exit(0);
    }
}
