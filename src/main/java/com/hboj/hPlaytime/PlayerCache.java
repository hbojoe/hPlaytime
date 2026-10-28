package com.hboj.hPlaytime;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Mutable indexes belong to the server thread; readers see only immutable publications. */
final class PlayerCache {
    private static final Comparator<PlaytimeLeaderboardEntry> ORDER = Comparator
        .comparingLong(PlaytimeLeaderboardEntry::millis).reversed().thenComparing(PlaytimeLeaderboardEntry::uuid);
    private final Map<UUID, PlayerData> records = new HashMap<>();
    private final Map<String, StoredPlayer> names = new HashMap<>();
    private final TreeSet<PlaytimeLeaderboardEntry> daily = new TreeSet<>(ORDER);
    private final TreeSet<PlaytimeLeaderboardEntry> monthly = new TreeSet<>(ORDER);
    private final TreeSet<PlaytimeLeaderboardEntry> alltime = new TreeSet<>(ORDER);
    private volatile ConcurrentHashMap<UUID, PlaytimeSnapshot> snapshots = new ConcurrentHashMap<>();
    private List<String> knownNames = List.of();
    private boolean namesChanged;
    private volatile List<PlaytimeLeaderboardEntry> topDay = List.of(), topMonth = List.of(), topAll = List.of();
    private String day = "", month = "";

    PlayerData get(UUID uuid, String fallback) { return records.getOrDefault(uuid, PlayerData.empty(uuid, fallback)); }
    boolean contains(UUID uuid) { return records.containsKey(uuid); }
    Map<UUID, PlayerData> copy() { return Map.copyOf(records); }

    void periods(String day, String month) {
        if (this.day.equals(day) && this.month.equals(month)) return;
        this.day = day; this.month = month;
        daily.clear(); monthly.clear(); alltime.clear();
        records.values().forEach(this::index);
        publish();
    }

    void replace(Map<UUID, PlayerData> players) {
        Map<UUID, PlaytimeSnapshot> replacement = new HashMap<>();
        players.forEach((uuid, player) -> replacement.put(uuid, player.snapshot(day, month)));
        records.clear(); names.clear(); daily.clear(); monthly.clear(); alltime.clear();
        namesChanged = true;
        players.values().forEach(this::put);
        snapshots = new ConcurrentHashMap<>(replacement);
        publish();
    }

    void put(PlayerData player) {
        PlayerData previous = records.put(player.uuid(), player);
        if (previous == null || !previous.name().equals(player.name())) namesChanged = true;
        if (previous != null) {
            unindex(previous);
            names.remove(previous.name().toLowerCase(Locale.ROOT), new StoredPlayer(previous.uuid(), previous.name()));
        }
        names.put(player.name().toLowerCase(Locale.ROOT), new StoredPlayer(player.uuid(), player.name()));
        index(player);
    }

    void remove(UUID uuid) {
        PlayerData previous = records.remove(uuid);
        if (previous == null) return;
        unindex(previous); snapshots.remove(uuid);
        names.remove(previous.name().toLowerCase(Locale.ROOT), new StoredPlayer(previous.uuid(), previous.name()));
        namesChanged = true;
    }

    private void index(PlayerData player) {
        snapshots.put(player.uuid(), player.snapshot(day, month));
        if (player.hidden()) return;
        add(daily, player, player.daily().getOrDefault(day, 0L));
        add(monthly, player, player.monthly().getOrDefault(month, 0L));
        add(alltime, player, player.alltime());
    }

    private void unindex(PlayerData player) {
        daily.remove(new PlaytimeLeaderboardEntry(player.uuid(), player.name(), player.daily().getOrDefault(day, 0L)));
        monthly.remove(new PlaytimeLeaderboardEntry(player.uuid(), player.name(), player.monthly().getOrDefault(month, 0L)));
        alltime.remove(new PlaytimeLeaderboardEntry(player.uuid(), player.name(), player.alltime()));
    }

    private void add(TreeSet<PlaytimeLeaderboardEntry> index, PlayerData player, long millis) {
        if (millis > 0) index.add(new PlaytimeLeaderboardEntry(player.uuid(), player.name(), millis));
    }

    void publish() {
        topDay = daily.stream().limit(100).toList();
        topMonth = monthly.stream().limit(100).toList();
        topAll = alltime.stream().limit(100).toList();
    }

    PlaytimeSnapshot snapshot(UUID uuid, String fallback) {
        return snapshots.getOrDefault(uuid, new PlaytimeSnapshot(uuid, fallback, 0, 0, 0, 0));
    }
    List<PlaytimeLeaderboardEntry> top(String period, int limit) {
        List<PlaytimeLeaderboardEntry> values = switch (period) {
            case "daily" -> topDay; case "monthly" -> topMonth; default -> topAll;
        };
        return values.subList(0, Math.min(Math.max(0, limit), values.size()));
    }
    Optional<StoredPlayer> find(String name) { return Optional.ofNullable(names.get(name.toLowerCase(Locale.ROOT))); }
    List<String> names() {
        if (namesChanged) {
            knownNames = names.values().stream().map(StoredPlayer::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
            namesChanged = false;
        }
        return knownNames;
    }
}
