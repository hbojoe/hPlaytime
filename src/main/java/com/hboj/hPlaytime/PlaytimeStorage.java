package com.hboj.hPlaytime;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlaytimeStorage extends AutoCloseable {
    void initialize() throws Exception;

    void updateName(UUID uuid, String playerName) throws Exception;

    void updateLastSeen(UUID uuid, String playerName, long lastSeenMillis) throws Exception;

    void addPlaytime(UUID uuid, String playerName, List<PlaytimeIncrement> increments) throws Exception;

    PlaytimeSnapshot getSnapshot(UUID uuid, String fallbackName, String todayKey, String monthKey) throws Exception;

    List<PlaytimeLeaderboardEntry> getTop(String periodType, String periodKey, int limit) throws Exception;

    List<String> getKnownPlayerNames() throws Exception;

    Optional<StoredPlayer> findByName(String playerName) throws Exception;

    void resetPlayer(UUID uuid) throws Exception;

    void resetAllPlayers() throws Exception;

    @Override
    void close() throws Exception;
}
