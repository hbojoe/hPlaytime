package com.hboj.hPlaytime;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Worker-confined local storage. A durable absolute redo plan prevents partial-operation duplicates. */
public final class LocalPlaytimeStorage implements PlaytimeStorage {
    private final Path folder;
    private StorageView view;
    public LocalPlaytimeStorage(File folder) { this.folder = folder.toPath(); }

    @Override public void initialize() throws Exception {
        Files.createDirectories(folder.resolve(".persistence/receipts"));
        replayPending();
    }

    @Override public StorageView loadView() throws Exception {
        if (view != null) return view;
        Map<UUID, PlayerData> players = new HashMap<>();
        try (var files = Files.list(folder)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".yml")).toList()) {
                UUID uuid;
                try { uuid = UUID.fromString(file.getFileName().toString().replaceFirst("\\.yml$", "")); }
                catch (IllegalArgumentException ignored) { continue; }
                PlayerData data = PlayerData.fromYaml(uuid, YamlFiles.read(file));
                if (!uuid.equals(data.uuid())) throw new IllegalStateException("UUID mismatch: " + file);
                players.put(uuid, data);
            }
        }
        Map<String, Object> state = YamlFiles.read(folder.resolve(".persistence/state.yml"));
        view = new StorageView(players, YamlFiles.number(state, "reset-millis"), readClocks(state));
        return view;
    }

    @Override public void apply(StorageMutation mutation) throws Exception {
        replayPending();
        Path receipt = folder.resolve(".persistence/receipts").resolve(mutation.id().toString());
        String hash = mutation.hash();
        if (Files.exists(receipt)) {
            if (!Files.readString(receipt).equals(hash)) throw new IllegalStateException("Operation ID reused with another payload");
            return;
        }
        StorageView before = loadView(), after = mutation.project(before);
        Map<String, Object> changed = new LinkedHashMap<>();
        after.players().forEach((uuid, data) -> {
            if (!data.equals(before.players().get(uuid))) changed.put(uuid.toString(), data.toYaml());
        });
        Map<String, Object> plan = Map.of("id", mutation.id().toString(), "hash", hash, "players", changed, "state", state(after));
        YamlFiles.write(folder.resolve(".persistence/pending.yml"), plan);
        finishPlan(plan);
        view = after;
    }

    @SuppressWarnings("unchecked") private void replayPending() throws Exception {
        Path plan = folder.resolve(".persistence/pending.yml");
        if (Files.exists(plan)) { finishPlan(YamlFiles.read(plan)); view = null; }
    }

    @SuppressWarnings("unchecked") private void finishPlan(Map<String, Object> plan) throws Exception {
        for (var entry : ((Map<String, Object>) plan.get("players")).entrySet()) {
            UUID uuid = UUID.fromString(entry.getKey());
            YamlFiles.write(folder.resolve(uuid + ".yml"), (Map<String, Object>) entry.getValue());
        }
        YamlFiles.write(folder.resolve(".persistence/state.yml"), (Map<String, Object>) plan.get("state"));
        YamlFiles.writeText(folder.resolve(".persistence/receipts").resolve(UUID.fromString(plan.get("id").toString()).toString()), plan.get("hash").toString());
        Files.delete(folder.resolve(".persistence/pending.yml"));
    }

    static Map<String, Object> state(StorageView view) {
        Map<String, Object> clocks = new HashMap<>();
        view.clocks().forEach((uuid, clock) -> clocks.put(uuid.toString(),
            Map.of("reset-millis", clock.resetMillis(), "metadata-millis", clock.metadataMillis())));
        return Map.of("reset-millis", view.resetMillis(), "clocks", clocks);
    }
    @SuppressWarnings("unchecked") static Map<UUID, StorageView.PlayerClock> readClocks(Map<String, Object> state) {
        Map<UUID, StorageView.PlayerClock> clocks = new HashMap<>();
        ((Map<String, Object>) state.getOrDefault("clocks", Map.of())).forEach((uuid, value) -> {
            Map<String, Object> entry = (Map<String, Object>) value;
            clocks.put(UUID.fromString(uuid), new StorageView.PlayerClock(YamlFiles.number(entry, "reset-millis"), YamlFiles.number(entry, "metadata-millis")));
        });
        return clocks;
    }

    /** Fixture/import utility; normal persistence always goes through apply(). */
    public void savePlayers(Map<UUID, PlayerData> players) throws Exception {
        for (PlayerData player : players.values()) YamlFiles.write(folder.resolve(player.uuid() + ".yml"), player.toYaml());
        view = null;
    }
    @Override public void close() {}
}
