package com.hboj.hPlaytime;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OrderedIoTest {
    @Test void ordersSavesResetAndCloseAndNeverRunsOnSubmittingThread() throws Exception {
        OrderedIo io = new OrderedIo(8);
        List<String> operations = new ArrayList<>();
        Thread submitting = Thread.currentThread();
        var first = io.submit(() -> { assertNotSame(submitting, Thread.currentThread()); operations.add("save-old"); return 1; });
        var reset = io.submit(() -> { operations.add("reset"); return 2; });
        var last = io.submit(() -> { operations.add("save-new"); operations.add("close"); return 3; });
        assertTrue(io.shutdown(Duration.ofSeconds(5)));
        assertEquals(1, first.get()); assertEquals(2, reset.get()); assertEquals(3, last.get());
        assertEquals(List.of("save-old", "reset", "save-new", "close"), operations);
        assertThrows(RejectedExecutionException.class, () -> io.submit(() -> 4));
    }

    @Test void rejectsOverloadWithoutBlockingOrCallerRunsAndDrainsAfterDeadline() throws Exception {
        OrderedIo io = new OrderedIo(1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            var first = io.submit(() -> { entered.countDown(); release.await(); return 1; });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queued = io.submit(() -> 2);
            assertThrows(RejectedExecutionException.class, () -> io.submit(() -> fail("must not run on caller")));
            assertFalse(io.shutdown(Duration.ofMillis(10)));
            release.countDown();
            assertTrue(io.shutdown(Duration.ofSeconds(5)));
            assertEquals(1, first.get()); assertEquals(2, queued.get());
        } finally { release.countDown(); io.shutdown(Duration.ofSeconds(5)); }
    }

    @Test void aFailedOperationDoesNotStrandLaterAcceptedWrites() throws Exception {
        OrderedIo io = new OrderedIo(2);
        var failed = io.submit(() -> { throw new IllegalStateException("database unavailable"); });
        var later = io.submit(() -> 42);
        assertTrue(io.shutdown(Duration.ofSeconds(5)));
        assertTrue(failed.isCompletedExceptionally());
        assertEquals(42, later.get());
    }
}
