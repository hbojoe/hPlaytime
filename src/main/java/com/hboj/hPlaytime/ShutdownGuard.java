package com.hboj.hPlaytime;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Normal JVM exit must give the independent recovery spool a chance to finish after a disable deadline. */
final class ShutdownGuard {
    private final OrderedIo io;
    private final Logger logger;
    private final AtomicReference<CompletableFuture<?>> checkpoint = new AtomicReference<>(CompletableFuture.completedFuture(null));
    private final Thread hook;
    private volatile int seconds = 30;

    ShutdownGuard(OrderedIo io, Logger logger) {
        this.io = io; this.logger = logger;
        hook = new Thread(() -> {
            try {
                awaitCheckpoint(Duration.ofSeconds(seconds));
                io.shutdown(Duration.ofSeconds(5));
            } catch (Exception failure) { logger.log(Level.SEVERE, "Shutdown recovery checkpoint could not be confirmed durable.", failure); }
        }, "hPlaytime-shutdown-recovery");
        Runtime.getRuntime().addShutdownHook(hook);
    }
    void timeout(int seconds) { this.seconds = seconds; }
    void checkpoint(CompletableFuture<?> staged) { checkpoint.set(staged); }
    void awaitCheckpoint(Duration timeout) throws Exception { checkpoint.get().get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
    void complete() {
        try { Runtime.getRuntime().removeShutdownHook(hook); }
        catch (IllegalStateException shuttingDown) { /* The hook is already protecting the final checkpoint. */ }
    }
}
