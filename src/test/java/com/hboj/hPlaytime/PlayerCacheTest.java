package com.hboj.hPlaytime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PlayerCacheTest {
    @Test void invalidatesNamesPrivacyRanksResetAndDateRollover() {
        PlayerCache cache = new PlayerCache();
        cache.periods("2026-10-27", "2026-10");
        UUID uuid = UUID.randomUUID();
        PlayerData player = TestData.player(uuid, 100);
        cache.put(player); cache.publish();
        assertEquals(100, cache.top("daily", 10).get(0).millis());
        assertEquals(List.of("Player"), cache.names());
        player = player.metadata("Renamed", true, 2);
        cache.put(player); cache.publish();
        assertTrue(cache.top("alltime", 10).isEmpty());
        assertTrue(cache.find("Player").isEmpty());
        assertEquals(uuid, cache.find("rEnAmEd").orElseThrow().uuid());
        assertEquals(List.of("Renamed"), cache.names());
        cache.put(player.metadata("Renamed", false, 2)); cache.publish();
        cache.periods("2026-10-28", "2026-10");
        assertTrue(cache.top("daily", 10).isEmpty());
        assertEquals(100, cache.snapshot(uuid, "fallback").monthMillis());
        assertEquals(0, cache.snapshot(uuid, "fallback").todayMillis());
        cache.put(player.reset()); cache.publish();
        assertEquals(0, cache.snapshot(uuid, "fallback").alltimeMillis());
        cache.replace(Map.of());
        assertTrue(cache.names().isEmpty());
    }

    @Test void concurrentPlaceholderReadersSeeImmutableCompleteRecords() {
        PlayerCache cache = new PlayerCache();
        cache.periods("2026-10-27", "2026-10");
        UUID uuid = UUID.randomUUID();
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 20000; i++) {
                PlaytimeSnapshot snapshot = cache.snapshot(uuid, "fallback");
                assertEquals(snapshot.todayMillis(), snapshot.alltimeMillis());
                assertEquals(snapshot.monthMillis(), snapshot.alltimeMillis());
                cache.top("alltime", 100).forEach(entry -> assertEquals(uuid, entry.uuid()));
            }
        });
        for (int i = 0; i < 1000; i++) { cache.put(TestData.player(uuid, i)); cache.publish(); }
        reader.join();
        assertThrows(UnsupportedOperationException.class, () -> cache.top("alltime", 10).clear());
    }
}
