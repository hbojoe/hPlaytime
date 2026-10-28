package com.hboj.hPlaytime;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Main-thread session accounting and operation outbox; placeholders read immutable publications. */
public final class PlaytimeManager {
    public static final String LEADERBOARD_HIDE_PERMISSION = "hplaytime.leaderboard.hide";
    private final HPlaytime plugin;
    private final EventManager eventManager;
    private final PlayerCache cache = new PlayerCache();
    private final Map<UUID, ActiveSession> sessions = new HashMap<>();
    private final MutationBuffer outbox = new MutationBuffer();
    private final LongSupplier clock;
    private StorageView base, projected;
    private volatile PluginSettings settings;
    private volatile Map<UUID, LiveSession> live = Map.of();

    public PlaytimeManager(HPlaytime plugin, PluginSettings settings, EventManager events, StorageView stored) {
        this(plugin, settings, events, stored, System::currentTimeMillis);
    }
    PlaytimeManager(HPlaytime plugin, PluginSettings settings, EventManager events, Map<UUID, PlayerData> stored, LongSupplier clock) {
        this(plugin, settings, events, new StorageView(stored, 0, Map.of()), clock);
    }
    PlaytimeManager(HPlaytime plugin, PluginSettings settings, EventManager events, StorageView stored, LongSupplier clock) {
        this.plugin = plugin; this.settings = settings; this.eventManager = events; this.clock = clock;
        base = projected = stored; updatePeriods(); cache.replace(stored.players());
    }

    private void updatePeriods() {
        ZonedDateTime now = Instant.ofEpochMilli(clock.getAsLong()).atZone(settings.zoneId());
        cache.periods(settings.dailyFormatter().format(now), settings.monthlyFormatter().format(now));
    }
    private void change(UUID uuid, StorageMutation.Change change) {
        outbox.add(uuid, change);
        PlayerData before = cache.get(uuid, change.name());
        cache.put(change.apply(uuid, before, projected.cutoff(uuid), 0));
    }

    public void startTracking(Player player) {
        UUID uuid = player.getUniqueId();
        if (sessions.containsKey(uuid)) return;
        long now = clock.getAsLong();
        sessions.put(uuid, new ActiveSession(now, player.getWorld().getName(), player.getName()));
        // A fresh join is authoritative metadata, even if a stale shared cache happens to match it.
        change(uuid, new StorageMutation.Change(player.getName(), player.hasPermission(LEADERBOARD_HIDE_PERMISSION), now, 0, List.of()));
        publishLive(); plugin.requestSave();
    }
    public void stopTracking(Player player) {
        flushPlayer(player);
        UUID uuid = player.getUniqueId();
        change(uuid, new StorageMutation.Change(player.getName(), cache.get(uuid, player.getName()).hidden(), 0, clock.getAsLong(), List.of()));
        sessions.remove(uuid); publishLive(); plugin.requestSave();
    }
    public void stopTrackingAll() { for (Player player : plugin.getServer().getOnlinePlayers()) stopTracking(player); }
    public void flushOnlinePlayers() {
        updatePeriods();
        for (Player player : plugin.getServer().getOnlinePlayers()) flushPlayer(player);
        cache.publish(); publishLive();
    }
    public void flushAll() { flushOnlinePlayers(); }
    public void handleActivity(Player player) {
        ActiveSession session = sessions.get(player.getUniqueId());
        if (session == null) { startTracking(player); return; }
        long now = clock.getAsLong();
        if (settings.afkEnabled() && now - session.lastActivity > settings.afkTimeoutMillis()) flushPlayer(player);
        session.lastActivity = now;
    }
    public void handleWorldChange(Player player, World previousWorld) {
        ActiveSession session = sessions.get(player.getUniqueId());
        if (session == null) { startTracking(player); return; }
        flushPlayer(player.getUniqueId(), player.getName(), previousWorld.getName());
        session.world = player.getWorld().getName(); session.lastActivity = clock.getAsLong(); publishLive();
    }

    public PlaytimeSnapshot getSnapshot(UUID uuid, String name) {
        updatePeriods(); Player player = plugin.getServer().getPlayer(uuid);
        if (player != null) flushPlayer(player);
        cache.publish(); publishLive(); return cache.snapshot(uuid, name);
    }
    public PlaytimeSnapshot getLiveSnapshot(UUID uuid, String fallback) {
        LiveSession session = live.get(uuid);
        if (session == null) return cache.snapshot(uuid, fallback);
        long end = session.afk ? Math.min(clock.getAsLong(), session.lastActivity + session.timeout) : clock.getAsLong();
        ZonedDateTime now = Instant.ofEpochMilli(clock.getAsLong()).atZone(session.settings.zoneId());
        String day = session.settings.dailyFormatter().format(now), month = session.settings.monthlyFormatter().format(now);
        PlaytimeSnapshot snapshot = session.data.snapshot(day, month);
        long today = snapshot.todayMillis(), monthly = snapshot.monthMillis(), total = snapshot.alltimeMillis();
        for (TimeSlice slice : slices(session.lastFlush, end, session.world, session.settings)) {
            long millis = slice.millisAfter(session.cutoff);
            if (slice.day().equals(day)) today += millis;
            if (slice.month().equals(month)) monthly += millis;
            total += millis;
        }
        return new PlaytimeSnapshot(uuid, session.data.name(), today, monthly, total, session.data.lastSeen());
    }

    private List<PlaytimeLeaderboardEntry> top(String type, int limit, boolean account) {
        if (account) flushOnlinePlayers(); return cache.top(type, limit);
    }
    public List<PlaytimeLeaderboardEntry> getTopDay(int limit) { return top("daily", limit, true); }
    public List<PlaytimeLeaderboardEntry> getTopMonth(int limit) { return top("monthly", limit, true); }
    public List<PlaytimeLeaderboardEntry> getTopAllTime(int limit) { return top("alltime", limit, true); }
    public List<PlaytimeLeaderboardEntry> getStoredTopDay(int limit) { return top("daily", limit, false); }
    public List<PlaytimeLeaderboardEntry> getStoredTopMonth(int limit) { return top("monthly", limit, false); }
    public List<PlaytimeLeaderboardEntry> getStoredTopAllTime(int limit) { return top("alltime", limit, false); }
    public Optional<StoredPlayer> findStoredPlayer(String name) { return cache.find(name); }
    public List<String> getKnownPlayerNames() { return cache.names(); }
    public TimeFormatter.Settings timeFormatterSettings() { return settings.timeFormatterSettings(); }
    public boolean lastSeenEnabled() { return settings.lastSeenEnabled(); }
    public String formatLastSeen(long millis) { return settings.lastSeenFormatter().format(Instant.ofEpochMilli(millis).atZone(settings.zoneId())); }
    public boolean isOnline(UUID uuid) { return live.containsKey(uuid); }

    private void flushPlayer(Player player) {
        UUID uuid = player.getUniqueId(); ActiveSession session = sessions.get(uuid);
        if (session == null) return;
        PlayerData stored = cache.get(uuid, player.getName());
        boolean hidden = player.hasPermission(LEADERBOARD_HIDE_PERMISSION);
        if (!stored.name().equals(player.getName()) || stored.hidden() != hidden) {
            change(uuid, new StorageMutation.Change(player.getName(), hidden, clock.getAsLong(), 0, List.of()));
            plugin.requestSave();
        }
        flushPlayer(uuid, player.getName(), session.world);
    }
    private void flushPlayer(UUID uuid, String name, String world) {
        ActiveSession session = sessions.get(uuid); if (session == null) return;
        long now = clock.getAsLong();
        long end = settings.afkEnabled() ? Math.min(now, session.lastActivity + settings.afkTimeoutMillis()) : now;
        List<TimeSlice> slices = slices(session.lastFlush, end, world, settings);
        if (!slices.isEmpty()) {
            change(uuid, new StorageMutation.Change(name, cache.get(uuid, name).hidden(), 0, 0, slices));
            // Main playtime resets do not reset independent event competitions.
            eventManager.addPlaytime(uuid, name, session.lastFlush, end);
        }
        session.lastFlush = Math.max(now, session.lastFlush);
    }

    public boolean resetPlayer(UUID uuid, String name) {
        Player player = plugin.getServer().getPlayer(uuid); if (player != null) flushPlayer(player);
        long now = clock.getAsLong(); outbox.reset(uuid, now);
        ActiveSession session = sessions.get(uuid); if (session != null) session.lastFlush = session.lastActivity = now;
        refresh(new PersistenceService.Update(base, Set.of(uuid))); plugin.requestSave(); return true;
    }
    public boolean resetAllPlayers() {
        flushOnlinePlayers(); long now = clock.getAsLong(); outbox.reset(null, now);
        sessions.values().forEach(session -> { session.lastFlush = now; session.lastActivity = now; });
        refresh(new PersistenceService.Update(base, base.players().keySet())); plugin.requestSave(); return true;
    }

    List<StorageMutation> capture() { return outbox.capture(); }
    List<StorageMutation> captureShutdown() { return outbox.captureShutdown(); }
    List<StorageMutation> recoverySnapshot() { return outbox.recoverySnapshot(); }
    boolean dirty() { return outbox.dirty(); }
    boolean uncertain() { return outbox.uncertain(); }
    Map<UUID, PlayerData> records() { return cache.copy(); }
    Map<UUID, PlayerData> pending() {
        Map<UUID, PlayerData> result = new HashMap<>();
        for (UUID uuid : outbox.affected(cache.copy().keySet())) result.put(uuid, cache.get(uuid, uuid.toString()));
        return Map.copyOf(result);
    }

    void acknowledge(List<StorageMutation> mutations, PersistenceService.Update update) {
        Set<UUID> changed = outbox.acknowledge(mutations, base.players().keySet());
        changed.addAll(update.changed()); refresh(new PersistenceService.Update(update.view(), changed));
    }
    void refresh(PersistenceService.Update update) {
        base = update.view(); projected = outbox.project(base);
        Set<UUID> affected = new HashSet<>(update.changed()); affected.addAll(outbox.affected(projected.players().keySet()));
        for (UUID uuid : affected) {
            PlayerData record = projected.players().get(uuid);
            if (record == null) cache.remove(uuid);
            else if (!record.equals(cache.get(uuid, record.name())) || !cache.contains(uuid)) cache.put(record);
        }
        updatePeriods(); cache.publish(); publishLive();
    }
    void applyReload(PluginSettings next, StorageView target) {
        settings = next;
        base = target; projected = outbox.project(base);
        updatePeriods(); cache.replace(projected.players()); publishLive();
    }

    private void publishLive() {
        Map<UUID, LiveSession> result = new HashMap<>();
        sessions.forEach((uuid, session) -> result.put(uuid, new LiveSession(cache.get(uuid, session.name),
            session.lastFlush, session.lastActivity, session.world, projected.cutoff(uuid),
            settings.afkEnabled(), settings.afkTimeoutMillis(), settings)));
        live = Map.copyOf(result);
    }

    static List<TimeSlice> slices(long start, long end, String world, PluginSettings settings) {
        if (end <= start || settings.isAfkWorld(world)) return List.of();
        List<TimeSlice> result = new ArrayList<>();
        ZonedDateTime cursor = Instant.ofEpochMilli(start).atZone(settings.zoneId()), until = Instant.ofEpochMilli(end).atZone(settings.zoneId());
        while (cursor.isBefore(until)) {
            ZonedDateTime next = cursor.toLocalDate().plusDays(1).atStartOfDay(settings.zoneId());
            ZonedDateTime segment = until.isBefore(next) ? until : next;
            result.add(new TimeSlice(cursor.toInstant().toEpochMilli(), segment.toInstant().toEpochMilli(),
                settings.dailyFormatter().format(cursor), settings.monthlyFormatter().format(cursor)));
            cursor = segment;
        }
        return List.copyOf(result);
    }
    static List<PlaytimeIncrement> increments(long start, long end, String world, PluginSettings settings) {
        return slices(start, end, world, settings).stream()
            .map(slice -> new PlaytimeIncrement(slice.day(), slice.month(), slice.to() - slice.from())).toList();
    }
    private static final class ActiveSession {
        long lastFlush, lastActivity; String world; final String name;
        ActiveSession(long now, String world, String name) { lastFlush = now; lastActivity = now; this.world = world; this.name = name; }
    }
    private record LiveSession(PlayerData data, long lastFlush, long lastActivity, String world, long cutoff,
                               boolean afk, long timeout, PluginSettings settings) {}
}
