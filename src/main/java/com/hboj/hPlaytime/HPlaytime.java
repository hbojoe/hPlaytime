package com.hboj.hPlaytime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class HPlaytime extends JavaPlugin {
    private Lang lang;
    private PlaytimeManager playtimeManager;
    private EventManager eventManager;
    private HPlaytimePlaceholderExpansion placeholderExpansion;
    private OrderedIo io;
    private PersistenceService persistence;
    private ShutdownGuard shutdownGuard;
    private BukkitTask heartbeat;
    private PluginSettings settings;
    private String activeIdentity;
    private final List<Completion<?>> completions = new ArrayList<>();
    private final List<Consumer<Boolean>> saveWaiters = new ArrayList<>();
    private boolean ready, closing, saving, refreshing, forceSave, reloading, reloadPreparing;
    private Consumer<Boolean> reloadCallback;
    private long ticks, nextSave, nextRefresh, retryAfter;
    private int shutdownSeconds = 30, retrySeconds = 5, refreshTicks = 100;

    @Override public void onEnable() {
        ready = closing = saving = refreshing = forceSave = reloading = reloadPreparing = false;
        ticks = nextSave = nextRefresh = retryAfter = 0;
        completions.clear(); saveWaiters.clear(); reloadCallback = null; placeholderExpansion = null; activeIdentity = null;
        io = new OrderedIo(8);
        persistence = new PersistenceService(getDataFolder().toPath(), getServer().getWorlds().stream()
            .map(world -> world.getWorldFolder().toPath().resolve("playerdata")).toList(), Path.of("usercache.json"));
        shutdownGuard = new ShutdownGuard(io, getLogger());
        PersistenceService service = persistence;
        for (String name : List.of("playtime", "hplaytime", "event")) {
            Objects.requireNonNull(getCommand(name)).setExecutor((sender, command, label, args) -> {
                sender.sendMessage("hPlaytime is loading its data. Please try again shortly."); return true;
            });
        }
        heartbeat = getServer().getScheduler().runTaskTimer(this, this::tick, 1, 1);
        watch(io.submit(service::readConfig), (text, failure) -> {
            if (failure != null) { startupFailure(failure); return; }
            try {
                settings = PluginSettings.load(text.config()); configureLifecycle(text.config());
                lang = new Lang(this, text.lang()); PluginSettings initialSettings = settings;
                watch(io.submit(() -> {
                    PersistenceService.Prepared prepared = service.prepare(initialSettings); service.activate(prepared);
                    return new Initial(prepared, service.loadEvents());
                }), (initial, loadFailure) -> {
                    if (loadFailure != null) { startupFailure(loadFailure); return; }
                    try {
                        eventManager = new EventManager(this, lang); eventManager.load(initial.events);
                        playtimeManager = new PlaytimeManager(this, settings, eventManager, initial.prepared.view());
                        activeIdentity = initial.prepared.identity(); ready = true;
                        getServer().getPluginManager().registerEvents(new PlayerSessionListener(playtimeManager), this);
                        getServer().getOnlinePlayers().forEach(playtimeManager::startTracking);
                        PlaytimeCommand commands = new PlaytimeCommand(this, playtimeManager, lang);
                        for (String name : List.of("playtime", "hplaytime", "event")) {
                            Objects.requireNonNull(getCommand(name)).setExecutor(commands);
                            Objects.requireNonNull(getCommand(name)).setTabCompleter(commands);
                        }
                        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
                            placeholderExpansion = new HPlaytimePlaceholderExpansion(this);
                            if (!placeholderExpansion.register()) getLogger().warning("Could not register hPlaytime placeholders.");
                        }
                        startFlushTask(settings);
                        getLogger().info("Playtime storage ready; additive persistence and shared cache refresh run off-thread.");
                    } catch (Exception invalid) { startupFailure(invalid); }
                });
            } catch (Exception invalid) { startupFailure(invalid); }
        });
    }

    private void startupFailure(Throwable failure) {
        getLogger().log(Level.SEVERE, "Could not start hPlaytime.", failure);
        getServer().getPluginManager().disablePlugin(this);
    }
    private void tick() {
        ticks++; pollCompletions(); if (!ready || closing) return;
        if (ticks % 20 == 0) { playtimeManager.flushOnlinePlayers(); eventManager.tick(); }
        if (saving || refreshing || reloadPreparing) return;
        if (ticks >= retryAfter && (forceSave || reloading || ticks >= nextSave)) dispatchSave();
        else if (settings.storageType() == PluginSettings.StorageType.MYSQL && !reloading && !playtimeManager.uncertain() && ticks >= nextRefresh) {
            refreshing = true; nextRefresh = ticks + refreshTicks;
            PersistenceService service = persistence;
            watch(io.submit(service::refresh), (update, failure) -> {
                refreshing = false;
                if (failure == null) { playtimeManager.flushOnlinePlayers(); playtimeManager.refresh(update); }
                else getLogger().log(Level.WARNING, "Shared cache refresh failed; retaining the current cache.", failure);
            });
        }
    }

    private void dispatchSave() {
        forceSave = false; nextSave = ticks + settings.flushIntervalTicks();
        boolean retrying = playtimeManager.uncertain();
        List<StorageMutation> mutations = playtimeManager.capture();
        EventManager.Pending events = eventManager.pending();
        PersistenceService.Batch batch = new PersistenceService.Batch(mutations, events.files());
        // New command acknowledgements must not attach to a retry of older SQL operations.
        List<Consumer<Boolean>> waiters = retrying ? List.of() : List.copyOf(saveWaiters);
        if (!retrying) saveWaiters.clear();
        boolean reloadBarrier = reloading && !retrying;
        if (batch.empty()) {
            waiters.forEach(callback -> callback.accept(true));
            if (reloadBarrier) beginReload();
            return;
        }
        saving = true; PersistenceService service = persistence;
        PersistenceService.Batch recovery = new PersistenceService.Batch(playtimeManager.recoverySnapshot(), events.files());
        CompletableFuture<RecoveryJournal.Checkpoint> staged = service.stage(activeIdentity, recovery);
        watch(io.submit(() -> service.persist(batch, staged)), (update, failure) -> {
            saving = false;
            if (failure == null) {
                playtimeManager.flushOnlinePlayers();
                playtimeManager.acknowledge(mutations, update); eventManager.acknowledge(events);
                nextRefresh = ticks + refreshTicks;
                waiters.forEach(callback -> callback.accept(true));
                if (reloadBarrier) beginReload();
                else if (!saveWaiters.isEmpty() || (retrying && playtimeManager.dirty())) requestSave();
            } else {
                getLogger().log(Level.SEVERE, "Persistence is unverified. Operation IDs/checkpoints are retained and will be retried.", failure);
                retryAfter = ticks + retrySeconds * 20L; forceSave = true;
                waiters.forEach(callback -> callback.accept(false));
                if (reloadBarrier) finishReload(false);
            }
        });
    }

    public boolean acceptsMutation() { return ready && !closing && !reloading && saveWaiters.size() < 64 && eventManager.canMutate(); }
    public void requestSave() { forceSave = true; }
    public void afterSave(Consumer<Boolean> callback) {
        if (saveWaiters.size() >= 64) { callback.accept(false); return; }
        saveWaiters.add(callback); requestSave();
    }
    public void reloadSettings(Consumer<Boolean> callback) {
        if (!acceptsMutation()) { callback.accept(false); return; }
        playtimeManager.flushOnlinePlayers(); eventManager.tick();
        reloading = true; reloadCallback = callback; requestSave();
    }
    private void beginReload() {
        reloadPreparing = true; PersistenceService service = persistence;
        watch(io.submit(service::readConfig), (text, failure) -> {
            if (failure != null) { reloadFailure(failure); return; }
            try {
                PluginSettings next = PluginSettings.load(text.config());
                watch(io.submit(() -> service.prepare(next)), (prepared, prepareFailure) -> {
                    if (prepareFailure != null) { reloadFailure(prepareFailure); return; }
                    playtimeManager.flushOnlinePlayers();
                    playtimeManager.applyReload(next, prepared.view());
                    settings = next; activeIdentity = prepared.identity();
                    configureLifecycle(text.config()); lang.reload(text.lang()); startFlushTask(next);
                    watch(io.submit(() -> { service.activate(prepared); return true; }), (ignored, activationFailure) -> {
                        if (activationFailure != null)
                            getLogger().log(Level.WARNING, "New storage activated, but closing old storage failed.", activationFailure);
                        finishReload(true);
                    });
                });
            } catch (Exception invalid) { reloadFailure(invalid); }
        });
    }
    private void reloadFailure(Throwable failure) {
        getLogger().log(Level.SEVERE, "Reload failed; retaining current storage and queued operations.", failure); finishReload(false);
    }
    private void finishReload(boolean success) {
        reloading = reloadPreparing = false; Consumer<Boolean> callback = reloadCallback; reloadCallback = null; requestSave();
        if (callback != null) callback.accept(success);
    }
    private void configureLifecycle(Map<String, Object> values) {
        SettingsReader config = new SettingsReader(values);
        shutdownSeconds = Math.max(1, Math.min(60, config.getInt("persistence.shutdown-timeout-seconds", 30)));
        retrySeconds = Math.max(1, Math.min(60, config.getInt("persistence.retry-interval-seconds", 5)));
        refreshTicks = Math.max(1, Math.min(60, config.getInt("storage.mysql.cache-refresh-seconds", 5))) * 20;
        shutdownGuard.timeout(shutdownSeconds);
    }

    @Override public void onDisable() {
        if (closing) return; closing = true;
        if (placeholderExpansion != null) placeholderExpansion.unregister();
        if (heartbeat != null) heartbeat.cancel();
        if (io == null) return;
        PersistenceService.Batch batch;
        if (ready && playtimeManager != null) {
            playtimeManager.stopTrackingAll(); eventManager.tick();
            batch = new PersistenceService.Batch(playtimeManager.captureShutdown(), eventManager.pending().files());
        } else batch = new PersistenceService.Batch(List.of(), Map.of());
        Logger logger = getLogger(); PersistenceService service = persistence; ShutdownGuard guard = shutdownGuard;
        // Admission only: journal I/O starts independently of any blocked database operation.
        CompletableFuture<RecoveryJournal.Checkpoint> staged = service.stage(activeIdentity, batch);
        guard.checkpoint(staged);
        CompletableFuture<Boolean> finalSave = io.submit(() -> {
            try { service.persist(batch, staged); return true; }
            catch (Exception failure) {
                String status = staged.isDone() && !staged.isCompletedExceptionally()
                    ? "Final database/file save is unverified; the recovery checkpoint must be retained."
                    : "Final recovery checkpoint could NOT be written; inspect the filesystem immediately.";
                logger.log(Level.SEVERE, status, failure); return false;
            } finally {
                try { service.close(); } finally { guard.complete(); }
            }
        });
        try {
            if (!io.shutdown(Duration.ofSeconds(shutdownSeconds)))
                logger.severe("Persistence drain deadline exceeded. Independent recovery spool/JVM shutdown hook protects the final checkpoint; do not force-kill.");
            else if (finalSave.isCompletedExceptionally()) logger.severe("Persistence close failed; inspect the server log and recovery checkpoint.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            logger.severe("Disable interrupted; accepted work and the shutdown recovery hook remain active.");
        }
        completions.clear(); saveWaiters.clear(); ready = false;
    }

    private <T> void watch(CompletableFuture<T> future, BiConsumer<T, Throwable> callback) { completions.add(new Completion<>(future, callback)); }
    private void pollCompletions() {
        List<Completion<?>> done = completions.stream().filter(value -> value.future.isDone()).toList();
        completions.removeAll(done); done.forEach(Completion::deliver);
    }
    private record Completion<T>(CompletableFuture<T> future, BiConsumer<T, Throwable> callback) {
        void deliver() {
            T result;
            try { result = future.join(); }
            catch (java.util.concurrent.CompletionException failure) { callback.accept(null, failure.getCause()); return; }
            callback.accept(result, null);
        }
    }
    private record Initial(PersistenceService.Prepared prepared, Map<String, Map<String, Object>> events) {}
    public void startFlushTask(PluginSettings settings) { nextSave = ticks + settings.flushIntervalTicks(); nextRefresh = ticks + refreshTicks; }
    public Lang lang() { return lang; }
    public PlaytimeManager playtimeManager() { return playtimeManager; }
    public EventManager eventManager() { return eventManager; }
}
