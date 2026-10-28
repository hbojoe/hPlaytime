package com.hboj.hPlaytime;

import java.util.Map;
import java.util.UUID;

/** A consistent database/file snapshot, including shared reset and metadata clocks. */
public record StorageView(Map<UUID, PlayerData> players, long resetMillis, Map<UUID, PlayerClock> clocks) {
    public record PlayerClock(long resetMillis, long metadataMillis) {}
    public StorageView { players = Map.copyOf(players); clocks = Map.copyOf(clocks); }
    public static StorageView empty() { return new StorageView(Map.of(), 0, Map.of()); }
    public long cutoff(UUID uuid) { return Math.max(resetMillis, clocks.getOrDefault(uuid, new PlayerClock(0, 0)).resetMillis()); }
}
