package com.hboj.hPlaytime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Independent bounded spool: a blocked database cannot prevent the final checkpoint reaching disk. */
final class RecoveryJournal {
    record Checkpoint(String identity, long generation, PersistenceService.Batch batch) {}
    private final Path folder;
    private final OrderedIo spool = new OrderedIo(4, "hPlaytime-recovery");
    private long generation;
    private boolean initialized;
    RecoveryJournal(Path folder) { this.folder = folder; }

    CompletableFuture<Checkpoint> checkpoint(String identity, PersistenceService.Batch batch) {
        if (batch.empty()) return CompletableFuture.completedFuture(null);
        return spool.submit(() -> {
            initializeGeneration();
            generation = Math.max(generation + 1, System.currentTimeMillis());
            // Also exceed durable file versions after a restart or a backward wall-clock correction.
            for (Object value : YamlFiles.read(folder.resolve(".file-versions.yml")).values())
                if (value instanceof Number number) generation = Math.max(generation, number.longValue() + 1);
            Path path = path(identity);
            Map<String, Object> existing = YamlFiles.read(path);
            generation = Math.max(generation, YamlFiles.number(existing, "generation") + 1);
            YamlFiles.writeText(folder.resolve(".recovery-sequence"), Long.toString(generation));
            YamlFiles.write(path, Map.of("format", 2, "generation", generation, "batch", PersistenceService.encode(batch)));
            return new Checkpoint(identity, generation, batch);
        });
    }

    CompletableFuture<Checkpoint> read(String identity) {
        return spool.submit(() -> {
            initializeGeneration();
            Path path = path(identity);
            if (!Files.exists(path)) return null;
            Map<String, Object> data = YamlFiles.read(path);
            if (YamlFiles.number(data, "format") != 2)
                throw new IllegalStateException("Unreleased absolute-total recovery file requires manual review before enabling shared storage: " + path);
            @SuppressWarnings("unchecked") Map<String, Object> batch = (Map<String, Object>) data.get("batch");
            long savedGeneration = YamlFiles.number(data, "generation");
            generation = Math.max(generation, savedGeneration);
            return new Checkpoint(identity, savedGeneration, PersistenceService.decode(batch));
        });
    }

    private void initializeGeneration() throws Exception {
        if (initialized) return;
        Path sequence = folder.resolve(".recovery-sequence");
        if (Files.exists(sequence)) {
            if (Files.size(sequence) > 64) throw new IllegalStateException("Invalid recovery sequence file");
            generation = Long.parseLong(Files.readString(sequence));
        }
        // Includes inactive backends and checkpoints restored from a backup with a future timestamp.
        try (var files = Files.list(folder)) {
            for (Path path : files.filter(file -> file.getFileName().toString().startsWith("recovery-") && file.toString().endsWith(".yml")).toList())
                generation = Math.max(generation, YamlFiles.number(YamlFiles.read(path), "generation"));
        }
        initialized = true;
    }

    CompletableFuture<Boolean> acknowledge(Checkpoint checkpoint) {
        if (checkpoint == null) return CompletableFuture.completedFuture(true);
        return spool.submit(() -> {
            Path path = path(checkpoint.identity());
            if (!Files.exists(path)) return true;
            Map<String, Object> current = YamlFiles.read(path);
            // A final shutdown snapshot may already supersede this in-flight save.
            if (YamlFiles.number(current, "generation") == checkpoint.generation()) Files.delete(path);
            return true;
        });
    }

    Path path(String identity) { return folder.resolve("recovery-" + identity + ".yml"); }
    boolean shutdown(Duration timeout) throws InterruptedException { return spool.shutdown(timeout); }
}
