package com.hboj.hPlaytime;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable accounting state. No Bukkit objects cross the persistence boundary. */
public record PlayerData(UUID uuid, String name, long lastSeen, boolean hidden,
                         long alltime, Map<String, Long> daily, Map<String, Long> monthly,
                         Map<String, Object> extra) {
    public PlayerData {
        daily = Map.copyOf(daily);
        monthly = Map.copyOf(monthly);
        extra = YamlFiles.freeze(extra);
    }

    public static PlayerData empty(UUID uuid, String name) {
        return new PlayerData(uuid, name, 0, false, 0, Map.of(), Map.of(), Map.of());
    }

    public PlayerData metadata(String name, boolean hidden, long seen) {
        if (this.name.equals(name) && this.hidden == hidden && lastSeen == seen) return this;
        return new PlayerData(uuid, name, seen, hidden, alltime, daily, monthly, extra);
    }

    public PlayerData add(List<PlaytimeIncrement> increments) {
        if (increments.isEmpty()) return this;
        Map<String, Long> days = new HashMap<>(daily);
        Map<String, Long> months = new HashMap<>(monthly);
        long total = alltime;
        for (PlaytimeIncrement increment : increments) {
            days.merge(increment.dayKey(), increment.millis(), Long::sum);
            months.merge(increment.monthKey(), increment.millis(), Long::sum);
            total += increment.millis();
        }
        return new PlayerData(uuid, name, lastSeen, hidden, total, days, months, extra);
    }

    public PlayerData reset() {
        return new PlayerData(uuid, name, lastSeen, hidden, 0, Map.of(), Map.of(), extra);
    }

    /** Replay accounting since a reload barrier, without copying the old backend's totals. */
    public PlayerData rebase(PlayerData before, PlayerData target) {
        return new PlayerData(uuid, name, Math.max(lastSeen, target.lastSeen), hidden,
            target.alltime + alltime - before.alltime,
            difference(daily, before.daily, target.daily),
            difference(monthly, before.monthly, target.monthly), target.extra);
    }

    private static Map<String, Long> difference(Map<String, Long> current, Map<String, Long> before,
                                               Map<String, Long> target) {
        Map<String, Long> result = new HashMap<>(target);
        current.forEach((key, value) -> {
            long delta = value - before.getOrDefault(key, 0L);
            if (delta != 0) result.merge(key, delta, Long::sum);
        });
        return result;
    }

    public PlaytimeSnapshot snapshot(String day, String month) {
        return new PlaytimeSnapshot(uuid, name, daily.getOrDefault(day, 0L),
            monthly.getOrDefault(month, 0L), alltime, lastSeen);
    }

    public Map<String, Object> toYaml() {
        Map<String, Object> data = new LinkedHashMap<>(extra);
        data.put("uuid", uuid.toString());
        data.put("name", name);
        data.put("last-seen-millis", lastSeen);
        data.put("leaderboard-hidden", hidden);
        data.put("alltime-millis", alltime);
        data.put("daily", nestedPeriods(daily));
        data.put("monthly", nestedPeriods(monthly));
        return data;
    }

    public static PlayerData fromYaml(UUID fallback, Map<String, Object> data) {
        UUID uuid = UUID.fromString(data.getOrDefault("uuid", fallback.toString()).toString());
        Map<String, Object> extra = new LinkedHashMap<>(data);
        List.of("uuid", "name", "last-seen-millis", "leaderboard-hidden", "alltime-millis", "daily", "monthly")
            .forEach(extra::remove);
        return new PlayerData(uuid, data.getOrDefault("name", uuid.toString()).toString(),
            YamlFiles.number(data, "last-seen-millis"), Boolean.TRUE.equals(data.get("leaderboard-hidden")),
            YamlFiles.number(data, "alltime-millis"), periods(data.get("daily")), periods(data.get("monthly")), extra);
    }

    private static Map<String, Long> periods(Object input) {
        Map<String, Long> result = new HashMap<>();
        readPeriods(input, "", result);
        return result;
    }

    private static void readPeriods(Object input, String prefix, Map<String, Long> result) {
        if (input instanceof Map<?, ?> map) map.forEach((key, value) -> {
            String path = prefix.isEmpty() ? key.toString() : prefix + "." + key;
            if (value instanceof Number number) result.put(path, number.longValue());
            else if (value instanceof Map<?, ?>) readPeriods(value, path, result);
            else throw new IllegalArgumentException("Invalid playtime period: " + path);
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedPeriods(Map<String, Long> periods) {
        Map<String, Object> root = new LinkedHashMap<>();
        periods.forEach((key, value) -> {
            // Bukkit configuration uses '.' as its path separator, including custom date formats.
            String[] parts = key.split("\\.", -1);
            Map<String, Object> node = root;
            for (int i = 0; i < parts.length - 1; i++) {
                if (!(node.get(parts[i]) instanceof Map<?, ?>)) node.put(parts[i], new LinkedHashMap<String, Object>());
                node = (Map<String, Object>) node.get(parts[i]);
            }
            node.put(parts[parts.length - 1], value);
        });
        return root;
    }
}
