package com.hboj.hPlaytime;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Stable operation IDs survive retries and recovery. ADD always carries deltas, never cached totals. */
public record StorageMutation(UUID id, Kind kind, Map<UUID, Change> changes, UUID target, long at) {
    public enum Kind { ADD, RESET_PLAYER, RESET_ALL }
    public record Change(String name, boolean hidden, long metadataAt, long lastSeen, List<TimeSlice> slices) {
        public Change { slices = List.copyOf(slices); }
        Change merge(Change next) {
            List<TimeSlice> joined = new ArrayList<>(slices);
            for (TimeSlice slice : next.slices) {
                if (!joined.isEmpty()) {
                    TimeSlice last = joined.get(joined.size() - 1);
                    if (last.to() == slice.from() && last.day().equals(slice.day()) && last.month().equals(slice.month())) {
                        joined.set(joined.size() - 1, new TimeSlice(last.from(), slice.to(), last.day(), last.month()));
                        continue;
                    }
                }
                joined.add(slice);
            }
            Change metadata = next.metadataAt >= metadataAt ? next : this;
            return new Change(metadata.name, metadata.hidden, Math.max(metadataAt, next.metadataAt),
                Math.max(lastSeen, next.lastSeen), joined);
        }
        PlayerData apply(UUID uuid, PlayerData before, long cutoff, long metadataClock) {
            List<PlaytimeIncrement> increments = slices.stream().filter(slice -> slice.millisAfter(cutoff) > 0)
                .map(slice -> new PlaytimeIncrement(slice.day(), slice.month(), slice.millisAfter(cutoff))).toList();
            boolean metadata = metadataAt > 0 && metadataAt >= metadataClock;
            return before.add(increments).metadata(metadata ? name : before.name(), metadata ? hidden : before.hidden(),
                Math.max(before.lastSeen(), lastSeen));
        }
    }
    public StorageMutation { changes = Map.copyOf(changes); }
    static StorageMutation add(Map<UUID, Change> changes) { return new StorageMutation(UUID.randomUUID(), Kind.ADD, changes, null, 0); }
    static StorageMutation reset(UUID uuid, long at) {
        return new StorageMutation(UUID.randomUUID(), uuid == null ? Kind.RESET_ALL : Kind.RESET_PLAYER, Map.of(), uuid, at);
    }

    StorageView project(StorageView before) {
        Map<UUID, PlayerData> players = new HashMap<>(before.players());
        Map<UUID, StorageView.PlayerClock> clocks = new HashMap<>(before.clocks());
        long reset = before.resetMillis();
        if (kind == Kind.RESET_ALL) {
            players.replaceAll((uuid, player) -> player.reset());
            reset = Math.max(reset, at);
        } else if (kind == Kind.RESET_PLAYER) {
            players.computeIfPresent(target, (uuid, player) -> player.reset());
            var clock = clocks.getOrDefault(target, new StorageView.PlayerClock(0, 0));
            clocks.put(target, new StorageView.PlayerClock(Math.max(clock.resetMillis(), at), clock.metadataMillis()));
        } else changes.forEach((uuid, change) -> {
            var clock = clocks.getOrDefault(uuid, new StorageView.PlayerClock(0, 0));
            PlayerData player = players.getOrDefault(uuid, PlayerData.empty(uuid, change.name()));
            players.put(uuid, change.apply(uuid, player, before.cutoff(uuid), clock.metadataMillis()));
            clocks.put(uuid, new StorageView.PlayerClock(clock.resetMillis(), Math.max(clock.metadataMillis(), change.metadataAt())));
        });
        return new StorageView(players, reset, clocks);
    }

    /** Sorted representation gives the receipt a deterministic payload fingerprint. */
    Map<String, Object> encode() {
        Map<String, Object> data = new TreeMap<>();
        data.put("id", id.toString()); data.put("kind", kind.name()); data.put("at", at);
        if (target != null) data.put("target", target.toString());
        Map<String, Object> players = new TreeMap<>();
        changes.forEach((uuid, change) -> {
            Map<String, Object> entry = new TreeMap<>();
            entry.put("name", change.name()); entry.put("hidden", change.hidden());
            entry.put("metadata-at", change.metadataAt()); entry.put("last-seen", change.lastSeen());
            entry.put("slices", change.slices().stream().map(slice -> List.of(slice.from(), slice.to(), slice.day(), slice.month())).toList());
            players.put(uuid.toString(), entry);
        });
        data.put("players", players);
        return data;
    }

    String hash() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(new Gson().toJson(encode()).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @SuppressWarnings("unchecked") static StorageMutation decode(Map<String, Object> data) {
        Map<UUID, Change> changes = new HashMap<>();
        ((Map<String, Object>) data.getOrDefault("players", Map.of())).forEach((id, value) -> {
            Map<String, Object> entry = (Map<String, Object>) value;
            List<TimeSlice> slices = new ArrayList<>();
            for (Object raw : (List<?>) entry.getOrDefault("slices", List.of())) {
                List<?> slice = (List<?>) raw;
                slices.add(new TimeSlice(((Number) slice.get(0)).longValue(), ((Number) slice.get(1)).longValue(),
                    slice.get(2).toString(), slice.get(3).toString()));
            }
            changes.put(UUID.fromString(id), new Change(entry.get("name").toString(), Boolean.TRUE.equals(entry.get("hidden")),
                YamlFiles.number(entry, "metadata-at"), YamlFiles.number(entry, "last-seen"), slices));
        });
        return new StorageMutation(UUID.fromString(data.get("id").toString()), Kind.valueOf(data.get("kind").toString()), changes,
            data.containsKey("target") ? UUID.fromString(data.get("target").toString()) : null, YamlFiles.number(data, "at"));
    }
}
