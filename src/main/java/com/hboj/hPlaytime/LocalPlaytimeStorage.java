package com.hboj.hPlaytime;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

public final class LocalPlaytimeStorage implements PlaytimeStorage {
    private final File playerDataFolder;

    public LocalPlaytimeStorage(File playerDataFolder) {
        this.playerDataFolder = playerDataFolder;
    }

    @Override
    public void initialize() {
        if (!playerDataFolder.exists() && !playerDataFolder.mkdirs()) {
            throw new IllegalStateException("Could not create playtime data folder: " + playerDataFolder);
        }
    }

    @Override
    public void updateName(UUID uuid, String playerName) throws IOException {
        YamlConfiguration data = loadPlayerData(uuid);
        data.set("uuid", uuid.toString());
        data.set("name", playerName);
        savePlayerData(uuid, data);
    }

    @Override
    public void updateLastSeen(UUID uuid, String playerName, long lastSeenMillis) throws IOException {
        YamlConfiguration data = loadPlayerData(uuid);
        data.set("uuid", uuid.toString());
        data.set("name", playerName);
        data.set("last-seen-millis", lastSeenMillis);
        savePlayerData(uuid, data);
    }

    @Override
    public void addPlaytime(UUID uuid, String playerName, List<PlaytimeIncrement> increments) throws IOException {
        if (increments.isEmpty()) {
            return;
        }

        YamlConfiguration data = loadPlayerData(uuid);
        data.set("uuid", uuid.toString());
        data.set("name", playerName);

        long totalMillis = 0L;
        for (PlaytimeIncrement increment : increments) {
            totalMillis += increment.millis();
            data.set("daily." + increment.dayKey(), data.getLong("daily." + increment.dayKey(), 0L) + increment.millis());
            data.set("monthly." + increment.monthKey(), data.getLong("monthly." + increment.monthKey(), 0L) + increment.millis());
        }

        data.set("alltime-millis", data.getLong("alltime-millis", 0L) + totalMillis);
        savePlayerData(uuid, data);
    }

    @Override
    public PlaytimeSnapshot getSnapshot(UUID uuid, String fallbackName, String todayKey, String monthKey) {
        YamlConfiguration data = loadPlayerData(uuid);
        return new PlaytimeSnapshot(
            uuid,
            data.getString("name", fallbackName),
            data.getLong("daily." + todayKey, 0L),
            data.getLong("monthly." + monthKey, 0L),
            data.getLong("alltime-millis", 0L),
            data.getLong("last-seen-millis", 0L)
        );
    }

    @Override
    public List<PlaytimeLeaderboardEntry> getTop(String periodType, String periodKey, int limit) {
        File[] files = playerDataFolder.listFiles((directory, name) -> name.endsWith(".yml"));
        if (files == null) {
            return List.of();
        }

        List<PlaytimeLeaderboardEntry> entries = new ArrayList<>();
        for (File file : files) {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
            String storedUuid = data.getString("uuid");
            String storedName = data.getString("name", storedUuid);
            long millis = getPeriodMillis(data, periodType, periodKey);
            if (storedUuid != null && millis > 0L) {
                try {
                    entries.add(new PlaytimeLeaderboardEntry(UUID.fromString(storedUuid), storedName, millis));
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed data files so one bad record does not break the full leaderboard.
                }
            }
        }

        return entries.stream()
            .sorted(Comparator.comparingLong(PlaytimeLeaderboardEntry::millis).reversed())
            .limit(limit)
            .toList();
    }

    @Override
    public Optional<StoredPlayer> findByName(String playerName) {
        File[] files = playerDataFolder.listFiles((directory, name) -> name.endsWith(".yml"));
        if (files == null) {
            return Optional.empty();
        }

        for (File file : files) {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
            String storedName = data.getString("name");
            String storedUuid = data.getString("uuid");
            if (storedName != null && storedUuid != null && storedName.equalsIgnoreCase(playerName)) {
                return Optional.of(new StoredPlayer(UUID.fromString(storedUuid), storedName));
            }
        }

        return Optional.empty();
    }

    @Override
    public void resetPlayer(UUID uuid) throws IOException {
        YamlConfiguration data = loadPlayerData(uuid);
        String storedUuid = data.getString("uuid", uuid.toString());
        String storedName = data.getString("name");
        long lastSeenMillis = data.getLong("last-seen-millis", 0L);
        data = new YamlConfiguration();
        data.set("uuid", storedUuid);
        if (storedName != null) {
            data.set("name", storedName);
        }
        if (lastSeenMillis > 0L) {
            data.set("last-seen-millis", lastSeenMillis);
        }
        data.set("alltime-millis", 0L);
        savePlayerData(uuid, data);
    }

    @Override
    public void resetAllPlayers() throws IOException {
        File[] files = playerDataFolder.listFiles((directory, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
            String storedUuid = data.getString("uuid");
            String storedName = data.getString("name");
            long lastSeenMillis = data.getLong("last-seen-millis", 0L);
            YamlConfiguration resetData = new YamlConfiguration();
            if (storedUuid != null) {
                resetData.set("uuid", storedUuid);
            }
            if (storedName != null) {
                resetData.set("name", storedName);
            }
            if (lastSeenMillis > 0L) {
                resetData.set("last-seen-millis", lastSeenMillis);
            }
            resetData.set("alltime-millis", 0L);
            resetData.save(file);
        }
    }

    @Override
    public void close() {
    }

    private YamlConfiguration loadPlayerData(UUID uuid) {
        return YamlConfiguration.loadConfiguration(playerDataFile(uuid));
    }

    private void savePlayerData(UUID uuid, YamlConfiguration data) throws IOException {
        data.save(playerDataFile(uuid));
    }

    private long getPeriodMillis(YamlConfiguration data, String periodType, String periodKey) {
        return switch (periodType) {
            case "daily" -> data.getLong("daily." + periodKey, 0L);
            case "monthly" -> data.getLong("monthly." + periodKey, 0L);
            case "alltime" -> data.getLong("alltime-millis", 0L);
            default -> 0L;
        };
    }

    private File playerDataFile(UUID uuid) {
        return new File(playerDataFolder, uuid + ".yml");
    }
}
