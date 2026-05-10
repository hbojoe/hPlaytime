package com.hboj.hPlaytime;

import java.util.List;
import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public final class HPlaytimePlaceholderExpansion extends PlaceholderExpansion {
    private static final int MAX_TOP_RANK = 100;

    private final HPlaytime plugin;

    public HPlaytimePlaceholderExpansion(HPlaytime plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "hplaytime";
    }

    @Override
    public String getAuthor() {
        List<String> authors = plugin.getPluginMeta().getAuthors();
        return authors.isEmpty() ? "hboj" : String.join(", ", authors);
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null || params.isBlank()) {
            return null;
        }

        String normalizedParams = params.toLowerCase(Locale.ROOT);
        String topValue = topPlaceholder(normalizedParams);
        if (topValue != null) {
            return topValue;
        }

        if (player == null) {
            return "";
        }

        String fallbackName = player.getName() == null ? player.getUniqueId().toString() : player.getName();
        PlaytimeSnapshot snapshot = plugin.playtimeManager().getLiveSnapshot(player.getUniqueId(), fallbackName);
        return playerPlaceholder(player, normalizedParams, snapshot);
    }

    private String playerPlaceholder(OfflinePlayer player, String params, PlaytimeSnapshot snapshot) {
        String durationPlaceholder = durationPlaceholder(params, snapshot);
        if (durationPlaceholder != null) {
            return durationPlaceholder;
        }

        return switch (params) {
            case "player", "name" -> snapshot.playerName() == null ? player.getUniqueId().toString() : snapshot.playerName();
            case "lastseen", "last_seen" -> lastSeen(player, snapshot);
            case "lastseen_millis", "last_seen_millis" -> Long.toString(snapshot.lastSeenMillis());
            default -> null;
        };
    }

    private String durationPlaceholder(String params, PlaytimeSnapshot snapshot) {
        if (params.equals("today") || params.equals("daily")) {
            return format(snapshot.todayMillis());
        }
        if (params.startsWith("today_")) {
            return durationValue(snapshot.todayMillis(), params.substring("today_".length()));
        }
        if (params.startsWith("daily_")) {
            return durationValue(snapshot.todayMillis(), params.substring("daily_".length()));
        }
        if (params.equals("month") || params.equals("monthly")) {
            return format(snapshot.monthMillis());
        }
        if (params.startsWith("month_")) {
            return durationValue(snapshot.monthMillis(), params.substring("month_".length()));
        }
        if (params.startsWith("monthly_")) {
            return durationValue(snapshot.monthMillis(), params.substring("monthly_".length()));
        }
        if (params.equals("alltime") || params.equals("total")) {
            return format(snapshot.alltimeMillis());
        }
        if (params.startsWith("alltime_")) {
            return durationValue(snapshot.alltimeMillis(), params.substring("alltime_".length()));
        }
        if (params.startsWith("total_")) {
            return durationValue(snapshot.alltimeMillis(), params.substring("total_".length()));
        }
        return null;
    }

    private String topPlaceholder(String params) {
        if (!params.startsWith("top_")) {
            return null;
        }

        String[] parts = params.split("_");
        if (parts.length != 4) {
            return null;
        }

        int rank;
        try {
            rank = Integer.parseInt(parts[2]);
        } catch (NumberFormatException exception) {
            return null;
        }

        if (rank < 1 || rank > MAX_TOP_RANK) {
            return null;
        }

        List<PlaytimeLeaderboardEntry> entries = topEntries(parts[1], rank);
        if (entries == null) {
            return null;
        }

        PlaytimeLeaderboardEntry entry = entries.size() >= rank ? entries.get(rank - 1) : null;
        return topValue(entry, parts[3]);
    }

    private List<PlaytimeLeaderboardEntry> topEntries(String period, int limit) {
        return switch (period) {
            case "today", "daily" -> plugin.playtimeManager().getStoredTopDay(limit);
            case "month", "monthly" -> plugin.playtimeManager().getStoredTopMonth(limit);
            case "alltime", "total" -> plugin.playtimeManager().getStoredTopAllTime(limit);
            default -> null;
        };
    }

    private String topValue(PlaytimeLeaderboardEntry entry, String field) {
        if (entry == null) {
            return switch (field) {
                case "name", "player", "uuid", "time", "formatted" -> "";
                case "millis", "milliseconds", "seconds", "minutes", "hours", "days" -> "0";
                default -> null;
            };
        }

        return switch (field) {
            case "name", "player" -> entry.playerName();
            case "uuid" -> entry.uuid().toString();
            case "time", "formatted" -> format(entry.millis());
            default -> durationValue(entry.millis(), field);
        };
    }

    private String durationValue(long millis, String valueType) {
        return switch (valueType) {
            case "time", "formatted" -> format(millis);
            case "millis", "milliseconds" -> Long.toString(millis);
            case "seconds" -> Long.toString(millis / 1000L);
            case "minutes" -> Long.toString(millis / 60_000L);
            case "hours" -> Long.toString(millis / 3_600_000L);
            case "days" -> Long.toString(millis / 86_400_000L);
            default -> null;
        };
    }

    private String format(long millis) {
        return TimeFormatter.format(millis, plugin.playtimeManager().timeFormatterSettings());
    }

    private String lastSeen(OfflinePlayer player, PlaytimeSnapshot snapshot) {
        if (plugin.getServer().getPlayer(player.getUniqueId()) != null) {
            return "online now";
        }
        if (snapshot.lastSeenMillis() <= 0L) {
            return "unknown";
        }
        return plugin.playtimeManager().formatLastSeen(snapshot.lastSeenMillis());
    }
}
