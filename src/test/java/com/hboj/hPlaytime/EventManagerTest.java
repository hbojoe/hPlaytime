package com.hboj.hPlaytime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class EventManagerTest {
    @Test void coalescesHundredsOfPlayerUpdatesAndRetainsChangesAfterOlderAcknowledgement() {
        AtomicLong clock = new AtomicLong(100000);
        EventManager events = new EventManager(mock(HPlaytime.class), mock(Lang.class), clock::get);
        events.create("test", "1h"); events.start("test");
        for (int i = 0; i < 100; i++) events.addPlaytime(UUID.randomUUID(), "Player", clock.get(), clock.get() + 1000);
        var inFlight = events.pending();
        assertEquals(1, inFlight.files().size());
        events.addPlaytime(UUID.randomUUID(), "New", clock.get(), clock.get() + 1000);
        events.acknowledge(inFlight);
        assertTrue(events.dirty());
        events.acknowledge(events.pending());
        assertFalse(events.dirty());
    }

    @Test void expiryIncludesEveryPlayerAndLogFilenamesDoNotCollide() throws Exception {
        AtomicLong clock = new AtomicLong(100000);
        HPlaytime plugin = mock(HPlaytime.class);
        PlaytimeManager manager = mock(PlaytimeManager.class);
        when(plugin.playtimeManager()).thenReturn(manager);
        when(manager.timeFormatterSettings()).thenReturn(TimeFormatter.Settings.defaults());
        EventManager events = new EventManager(plugin, mock(Lang.class), clock::get);
        events.create("test", "1s"); events.start("test");
        long start = clock.get(); clock.addAndGet(2000);
        events.addPlaytime(UUID.randomUUID(), "One", start, clock.get());
        events.addPlaytime(UUID.randomUUID(), "Two", start, clock.get());
        assertTrue(events.event("test").orElseThrow().active());
        events.tick();
        assertFalse(events.event("test").orElseThrow().active());
        assertEquals(2, events.event("test").orElseThrow().players().size());
        events.delete("test");
        assertEquals(2, events.pending().files().keySet().stream().filter(path -> path.startsWith("event-logs/")).count());
    }

    @Test void aDeletedEventCannotBeResurrectedByAnEarlierSaveAcknowledgement() {
        EventManager events = new EventManager(mock(HPlaytime.class), mock(Lang.class));
        events.create("test", "1h");
        var old = events.pending();
        events.delete("test");
        events.acknowledge(old);
        assertTrue(events.pending().files().get("events/test.yml").deleted());
        events.create("test", "2h");
        assertFalse(events.pending().files().get("events/test.yml").deleted());
        assertEquals(7200000L, events.pending().files().get("events/test.yml").data().get("duration-millis"));
    }
}
