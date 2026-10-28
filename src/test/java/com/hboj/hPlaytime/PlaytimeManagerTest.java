package com.hboj.hPlaytime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaytimeManagerTest {
    HPlaytime plugin;
    Server server;
    Player player;
    World world;
    EventManager events;
    PlaytimeManager manager;
    UUID uuid;
    AtomicLong now;
    List<Player> online;
    StorageView serverView = StorageView.empty();
    void commit() {
        var operations = manager.capture();
        serverView = TestData.apply(serverView, operations);
        manager.acknowledge(operations, new PersistenceService.Update(serverView, serverView.players().keySet()));
    }

    @BeforeEach void setup() throws Exception {
        plugin = mock(HPlaytime.class); server = mock(Server.class); player = mock(Player.class); world = mock(World.class);
        now = new AtomicLong(Instant.parse("2026-10-27T12:00:00Z").toEpochMilli());
        uuid = UUID.randomUUID(); online = new ArrayList<>(List.of(player));
        when(plugin.getServer()).thenReturn(server);
        when(server.getOnlinePlayers()).thenAnswer(ignored -> online);
        when(server.getPlayer(uuid)).thenReturn(player);
        when(player.getUniqueId()).thenReturn(uuid); when(player.getName()).thenReturn("Player");
        when(player.getWorld()).thenReturn(world); when(world.getName()).thenReturn("world");
        events = new EventManager(plugin, mock(Lang.class), now::get);
        manager = new PlaytimeManager(plugin, TestData.settings(), events, Map.of(), now::get);
        when(plugin.playtimeManager()).thenReturn(manager);
        manager.startTracking(player);
        commit();
    }

    @Test void ordinaryInteractionsDoNotFlushAndAfkReturnDoesNotCountIdleGap() {
        now.addAndGet(5000); manager.handleActivity(player);
        assertTrue(manager.pending().isEmpty());
        now.addAndGet(11000); manager.handleActivity(player);
        assertEquals(15000, manager.pending().get(uuid).alltime()); // Eligible through last activity + 10s.
        now.addAndGet(2000); manager.flushOnlinePlayers();
        assertEquals(17000, manager.getSnapshot(uuid, "Player").alltimeMillis());
    }

    @Test void olderSaveAcknowledgementCannotClearAResetOrNewPlaytime() {
        now.addAndGet(5000); manager.flushOnlinePlayers();
        var inFlight = manager.capture();
        manager.resetPlayer(uuid, "Player");
        serverView = TestData.apply(serverView, inFlight);
        manager.acknowledge(inFlight, new PersistenceService.Update(serverView, serverView.players().keySet()));
        assertEquals(0, manager.pending().get(uuid).alltime());
        var reset = manager.capture();
        now.addAndGet(2000); manager.flushOnlinePlayers();
        serverView = TestData.apply(serverView, reset);
        manager.acknowledge(reset, new PersistenceService.Update(serverView, serverView.players().keySet()));
        assertEquals(2000, manager.pending().get(uuid).alltime());
    }

    @Test void disconnectReconnectAndShutdownRetainLastSeenAndUnsavedTotals() {
        now.addAndGet(3000); manager.stopTracking(player);
        assertFalse(manager.isOnline(uuid));
        assertEquals(now.get(), manager.pending().get(uuid).lastSeen());
        long seen = now.get();
        now.addAndGet(10000); manager.startTracking(player);
        now.addAndGet(2000); manager.stopTrackingAll();
        assertEquals(5000, manager.pending().get(uuid).alltime());
        assertTrue(manager.pending().get(uuid).lastSeen() > seen);
        assertFalse(manager.isOnline(uuid));
    }

    @Test void backendReloadReplaysOnlyPostBarrierTimeAndDoesNotLoseAFkState() throws Exception {
        now.addAndGet(2000); manager.flushOnlinePlayers();
        var baseline = manager.records();
        commit();
        now.addAndGet(3000); manager.flushOnlinePlayers();
        manager.applyReload(TestData.settings(), new StorageView(Map.of(uuid, TestData.player(uuid, 100000)), 0, Map.of()));
        assertEquals(103000, manager.getSnapshot(uuid, "Player").alltimeMillis());
        now.addAndGet(20000); manager.flushOnlinePlayers();
        assertEquals(108000, manager.getSnapshot(uuid, "Player").alltimeMillis());
    }

    @Test void placeholderReadsOffThreadUseOnlyPublishedStateAndNoBukkitCalls() {
        now.addAndGet(2000); manager.flushOnlinePlayers();
        clearInvocations(plugin, server, player, world);
        now.addAndGet(2000);
        CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 1000; i++) {
                assertEquals(4000, manager.getLiveSnapshot(uuid, "fallback").alltimeMillis());
                assertTrue(manager.isOnline(uuid));
                manager.getStoredTopAllTime(100);
            }
        }).join();
        verifyNoInteractions(plugin, server, player, world);
    }

    @Test void afkWorldAndWorldChangesPreserveOnlyEligibleTime() {
        World afk = mock(World.class); when(afk.getName()).thenReturn("afk");
        now.addAndGet(3000); when(player.getWorld()).thenReturn(afk); manager.handleWorldChange(player, world);
        now.addAndGet(3000); manager.flushOnlinePlayers();
        assertEquals(3000, manager.getSnapshot(uuid, "Player").alltimeMillis());
        when(player.getWorld()).thenReturn(world); manager.handleWorldChange(player, afk);
        now.addAndGet(2000); manager.flushOnlinePlayers();
        assertEquals(5000, manager.getSnapshot(uuid, "Player").alltimeMillis());
    }

    @Test void splitsDailyAndMonthlyRollover() throws Exception {
        long from = Instant.parse("2026-10-31T23:59:58Z").toEpochMilli();
        var increments = PlaytimeManager.increments(from, from + 4000, "world", TestData.settings());
        assertEquals(List.of(new PlaytimeIncrement("2026-10-31", "2026-10", 2000),
            new PlaytimeIncrement("2026-11-01", "2026-11", 2000)), increments);
    }

    @Test void preservesTheFullTwentyFiveHourDayAtDaylightSavingRollover() {
        PluginSettings chicago = PluginSettings.load(Map.of("date-format", Map.of("timezone", "America/Chicago")));
        long start = java.time.ZonedDateTime.parse("2026-11-01T00:00:00-05:00[America/Chicago]").toInstant().toEpochMilli();
        long end = java.time.ZonedDateTime.parse("2026-11-02T00:00:00-06:00[America/Chicago]").toInstant().toEpochMilli();
        assertEquals(List.of(new PlaytimeIncrement("2026-11-01", "2026-11", 25 * 3600000L)),
            PlaytimeManager.increments(start, end, "world", chicago));
    }

    @Test void sharedCacheRefreshAddsRemoteContributionsWithoutDroppingOrDoubleCountingLocalPendingTime() {
        now.addAndGet(200); manager.flushOnlinePlayers();
        StorageView remote = new StorageView(Map.of(uuid, TestData.player(uuid, 100)), 0, serverView.clocks());
        manager.refresh(new PersistenceService.Update(remote, java.util.Set.of(uuid)));
        assertEquals(300, manager.getSnapshot(uuid, "Player").alltimeMillis());
        var pending = manager.capture();
        StorageView committed = TestData.apply(remote, pending);
        manager.acknowledge(pending, new PersistenceService.Update(committed, java.util.Set.of(uuid)));
        assertEquals(300, manager.getSnapshot(uuid, "Player").alltimeMillis());
        assertEquals(300, manager.getStoredTopAllTime(10).get(0).millis());
    }

    @Test void remoteResetClipsPendingIntervalsAndInvalidatesCachedTotals() {
        long start = now.get(); now.addAndGet(5000); manager.flushOnlinePlayers();
        StorageView reset = new StorageView(Map.of(uuid, PlayerData.empty(uuid, "Player")), start + 2000, serverView.clocks());
        manager.refresh(new PersistenceService.Update(reset, java.util.Set.of(uuid)));
        assertEquals(3000, manager.getSnapshot(uuid, "Player").alltimeMillis());
        var pending = manager.capture(); StorageView committed = TestData.apply(reset, pending);
        manager.acknowledge(pending, new PersistenceService.Update(committed, java.util.Set.of(uuid)));
        assertEquals(3000, manager.getSnapshot(uuid, "Player").alltimeMillis());
    }

    @Test void sharedNameAndVisibilityChangesInvalidateOfflineRanksAndLookups() {
        manager.stopTracking(player); commit(); online.clear(); when(server.getPlayer(uuid)).thenReturn(null);
        var hidden = TestData.player(uuid, 100).metadata("Renamed", true, now.get());
        StorageView remote = new StorageView(Map.of(uuid, hidden), 0, serverView.clocks());
        manager.refresh(new PersistenceService.Update(remote, java.util.Set.of(uuid)));
        assertTrue(manager.getStoredTopAllTime(10).isEmpty());
        assertTrue(manager.findStoredPlayer("Player").isEmpty());
        assertEquals(uuid, manager.findStoredPlayer("Renamed").orElseThrow().uuid());
        assertEquals("Renamed", manager.getLiveSnapshot(uuid, "fallback").playerName());
    }
}
