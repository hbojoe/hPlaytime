package com.hboj.hPlaytime;

import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TestData {
    static PluginSettings settings() {
        return PluginSettings.load(Map.of("date-format", Map.of("timezone", "UTC"),
            "afk", Map.of("enabled", true, "timeout-seconds", 10, "worlds", List.of("afk"))));
    }
    static PlayerData player(UUID uuid, long millis) {
        return PlayerData.empty(uuid, "Player").add(List.of(new PlaytimeIncrement("2026-10-27", "2026-10", millis)));
    }
    static StorageMutation add(UUID uuid, long from, long to) {
        return StorageMutation.add(Map.of(uuid, new StorageMutation.Change("Player", false, 0, 0,
            List.of(new TimeSlice(from, to, "2026-10-27", "2026-10")))));
    }
    static StorageView apply(StorageView view, List<StorageMutation> mutations) {
        for (StorageMutation mutation : mutations) view = mutation.project(view);
        return view;
    }
}
