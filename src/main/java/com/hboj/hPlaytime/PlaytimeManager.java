package com.hboj.hPlaytime;

import java.io.File;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class PlaytimeManager {
    private final HPlaytime plugin;
    private final EventManager eventManager;
    private final Map<UUID, ActiveSession> activeSessions = new HashMap<>();
    private PluginSettings settings;
    private PlaytimeStorage storage;

    public PlaytimeManager(HPlaytime plugin, PluginSettings settings, EventManager eventManager) throws Exception {
        this.plugin = plugin;
        this.eventManager = eventManager;
        reload(settings);
    }

    public void startTracking(Player player) {
        long now = System.currentTimeMillis();
        activeSessions.put(player.getUniqueId(), new ActiveSession(now, player.getWorld().getName()));
        updateStoredName(player.getUniqueId(), player.getName());
    }

    public void stopTracking(Player player) {
        flushPlayer(player);
        updateLastSeen(player.getUniqueId(), player.getName(), System.currentTimeMillis());
        activeSessions.remove(player.getUniqueId());
    }

    public void stopTrackingAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            stopTracking(player);
        }
    }

    public void flushOnlinePlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            flushPlayer(player);
        }
    }

    public void flushAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            flushPlayer(player);
        }
    }

    public void handleActivity(Player player) {
        ActiveSession activeSession = activeSessions.get(player.getUniqueId());
        if (activeSession == null) {
            startTracking(player);
            return;
        }

        long now = System.currentTimeMillis();
        if (settings.afkEnabled() && now - activeSession.lastActivityMillis() > settings.afkTimeoutMillis()) {
            flushPlayer(player);
        }

        activeSession.setLastActivityMillis(now);
    }

    public void handleWorldChange(Player player, World previousWorld) {
        ActiveSession activeSession = activeSessions.get(player.getUniqueId());
        if (activeSession == null) {
            startTracking(player);
            return;
        }

        flushPlayer(player.getUniqueId(), player.getName(), previousWorld.getName());
        long now = System.currentTimeMillis();
        activeSession.setCurrentWorldName(player.getWorld().getName());
        activeSession.setLastActivityMillis(now);
    }

    public PlaytimeSnapshot getSnapshot(UUID uuid, String fallbackName) {
        Player onlinePlayer = plugin.getServer().getPlayer(uuid);
        if (onlinePlayer != null) {
            flushPlayer(onlinePlayer);
        }

        ZonedDateTime now = ZonedDateTime.now(settings.zoneId());
        String todayKey = settings.dailyFormatter().format(now);
        String monthKey = settings.monthlyFormatter().format(now);

        try {
            return storage.getSnapshot(uuid, fallbackName, todayKey, monthKey);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not load playtime for " + fallbackName, exception);
            return new PlaytimeSnapshot(uuid, fallbackName, 0L, 0L, 0L, 0L);
        }
    }

    public Optional<StoredPlayer> findStoredPlayer(String playerName) {
        try {
            return storage.findByName(playerName);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not search playtime data for " + playerName, exception);
            return Optional.empty();
        }
    }

    public void reload(PluginSettings newSettings) throws Exception {
        flushAll();

        PlaytimeStorage newStorage = createStorage(newSettings);
        newStorage.initialize();

        PlaytimeStorage oldStorage = storage;
        settings = newSettings;
        storage = newStorage;
        if (oldStorage != null) {
            oldStorage.close();
        }

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            activeSessions.put(player.getUniqueId(), new ActiveSession(System.currentTimeMillis(), player.getWorld().getName()));
            updateStoredName(player.getUniqueId(), player.getName());
        }
    }

    public void close() {
        try {
            if (storage != null) {
                storage.close();
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Could not close playtime storage.", exception);
        }
    }

    public TimeFormatter.Settings timeFormatterSettings() {
        return settings.timeFormatterSettings();
    }

    public boolean lastSeenEnabled() {
        return settings.lastSeenEnabled();
    }

    public String formatLastSeen(long lastSeenMillis) {
        return settings.lastSeenFormatter().format(Instant.ofEpochMilli(lastSeenMillis).atZone(settings.zoneId()));
    }

    private void flushPlayer(Player player) {
        ActiveSession activeSession = activeSessions.get(player.getUniqueId());
        String worldName = activeSession == null ? player.getWorld().getName() : activeSession.currentWorldName();
        flushPlayer(player.getUniqueId(), player.getName(), worldName);
    }

    private void flushPlayer(UUID uuid, String playerName, String worldName) {
        ActiveSession activeSession = activeSessions.get(uuid);
        if (activeSession == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now <= activeSession.lastFlushMillis()) {
            return;
        }

        try {
            long eligibleEndMillis = eligibleEndMillis(activeSession, now);
            List<PlaytimeIncrement> increments = createIncrements(activeSession, worldName, eligibleEndMillis);
            storage.addPlaytime(uuid, playerName, increments);
            if (!increments.isEmpty()) {
                eventManager.addPlaytime(uuid, playerName, activeSession.lastFlushMillis(), eligibleEndMillis);
            }
            activeSession.setLastFlushMillis(now);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save playtime for " + playerName, exception);
        }
    }

    public boolean resetPlayer(UUID uuid, String playerName) {
        Player onlinePlayer = plugin.getServer().getPlayer(uuid);
        if (onlinePlayer != null) {
            flushPlayer(onlinePlayer);
        }

        try {
            storage.resetPlayer(uuid);
            ActiveSession activeSession = activeSessions.get(uuid);
            if (activeSession != null) {
                long now = System.currentTimeMillis();
                activeSession.setLastFlushMillis(now);
                activeSession.setLastActivityMillis(now);
            }
            return true;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not reset playtime for " + playerName, exception);
            return false;
        }
    }

    public boolean resetAllPlayers() {
        flushAll();
        try {
            storage.resetAllPlayers();
            long now = System.currentTimeMillis();
            for (ActiveSession activeSession : activeSessions.values()) {
                activeSession.setLastFlushMillis(now);
                activeSession.setLastActivityMillis(now);
            }
            return true;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not reset all playtime.", exception);
            return false;
        }
    }

    private void updateStoredName(UUID uuid, String playerName) {
        try {
            storage.updateName(uuid, playerName);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not update playtime name for " + playerName, exception);
        }
    }

    private void updateLastSeen(UUID uuid, String playerName, long lastSeenMillis) {
        try {
            storage.updateLastSeen(uuid, playerName, lastSeenMillis);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not update last seen for " + playerName, exception);
        }
    }

    private long eligibleEndMillis(ActiveSession activeSession, long now) {
        if (!settings.afkEnabled()) {
            return now;
        }

        return Math.min(now, activeSession.lastActivityMillis() + settings.afkTimeoutMillis());
    }

    private List<PlaytimeIncrement> createIncrements(ActiveSession activeSession, String worldName, long eligibleEndMillis) {
        if (settings.isAfkWorld(worldName)) {
            return List.of();
        }

        if (eligibleEndMillis <= activeSession.lastFlushMillis()) {
            return List.of();
        }

        List<PlaytimeIncrement> increments = new ArrayList<>();
        ZonedDateTime cursor = Instant.ofEpochMilli(activeSession.lastFlushMillis()).atZone(settings.zoneId());
        ZonedDateTime end = Instant.ofEpochMilli(eligibleEndMillis).atZone(settings.zoneId());

        while (cursor.isBefore(end)) {
            ZonedDateTime nextDay = cursor.toLocalDate().plusDays(1).atStartOfDay(settings.zoneId());
            ZonedDateTime segmentEnd = end.isBefore(nextDay) ? end : nextDay;
            long segmentMillis = segmentEnd.toInstant().toEpochMilli() - cursor.toInstant().toEpochMilli();

            increments.add(new PlaytimeIncrement(
                settings.dailyFormatter().format(cursor),
                settings.monthlyFormatter().format(cursor),
                segmentMillis
            ));

            cursor = segmentEnd;
        }

        return increments;
    }

    private PlaytimeStorage createStorage(PluginSettings settings) {
        if (settings.storageType() == PluginSettings.StorageType.MYSQL) {
            return new MysqlPlaytimeStorage(settings.mysqlSettings());
        }
        return new LocalPlaytimeStorage(new File(plugin.getDataFolder(), "data"));
    }

    private static final class ActiveSession {
        private long lastFlushMillis;
        private long lastActivityMillis;
        private String currentWorldName;

        private ActiveSession(long lastFlushMillis, String currentWorldName) {
            this.lastFlushMillis = lastFlushMillis;
            this.lastActivityMillis = lastFlushMillis;
            this.currentWorldName = currentWorldName;
        }

        private long lastFlushMillis() {
            return lastFlushMillis;
        }

        private void setLastFlushMillis(long lastFlushMillis) {
            this.lastFlushMillis = lastFlushMillis;
        }

        private long lastActivityMillis() {
            return lastActivityMillis;
        }

        private void setLastActivityMillis(long lastActivityMillis) {
            this.lastActivityMillis = lastActivityMillis;
        }

        private String currentWorldName() {
            return currentWorldName;
        }

        private void setCurrentWorldName(String currentWorldName) {
            this.currentWorldName = currentWorldName;
        }
    }
}
