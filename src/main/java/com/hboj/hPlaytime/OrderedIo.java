package com.hboj.hPlaytime;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** FIFO, bounded admission, never CallerRunsPolicy. Callbacks are polled on the main thread. */
final class OrderedIo {
    private final ThreadPoolExecutor executor;

    OrderedIo(int capacity) {
        this(capacity, "hPlaytime-persistence");
    }
    OrderedIo(int capacity, String name) {
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(capacity), runnable -> {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(false);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    <T> CompletableFuture<T> submit(Callable<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try { future.complete(action.call()); }
            catch (Throwable failure) { future.completeExceptionally(failure); }
        });
        return future;
    }

    boolean shutdown(Duration timeout) throws InterruptedException {
        executor.shutdown();
        // Only onDisable waits: bounded drain, never during live ticks.
        return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
