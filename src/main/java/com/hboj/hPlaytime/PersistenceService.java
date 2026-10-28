package com.hboj.hPlaytime;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Database/file worker state. Only the bounded journal spool may perform I/O independently. */
final class PersistenceService implements AutoCloseable {
    record ConfigText(Map<String, Object> config, Map<String, Object> lang) {
        ConfigText { config = YamlFiles.freeze(config); lang = YamlFiles.freeze(lang); }
    }
    record FileUpdate(String path, boolean deleted, Map<String, Object> data) {
        FileUpdate { data = YamlFiles.freeze(data); }
    }
    record Batch(List<StorageMutation> mutations, Map<String, FileUpdate> files) {
        Batch { mutations = List.copyOf(mutations); files = Map.copyOf(files); }
        boolean empty() { return mutations.isEmpty() && files.isEmpty(); }
    }
    record Prepared(PlaytimeStorage storage, String identity, StorageView view) {
        Map<UUID, PlayerData> players() { return view.players(); }
    }
    record Update(StorageView view, Set<UUID> changed) { Update { changed = Set.copyOf(changed); } }
    private final Path folder;
    private final Function<PluginSettings, PlaytimeStorage> factory;
    private final RecoveryJournal journal;
    private PlaytimeStorage storage;
    private String identity;
    private Prepared candidate;
    private List<Path> vanillaPlayerFolders = List.of();
    private Path vanillaUserCache;
    private Map<UUID, String> vanillaNames = Map.of();
    private FileChannel lockChannel;
    private FileLock folderLock;
    private StorageView published = StorageView.empty();
    private final Map<String, Long> fileVersions = new HashMap<>();
    private boolean versionsLoaded, versionsDirty;

    PersistenceService(Path folder, List<Path> playerFolders, Path userCache) {
        this(folder); vanillaPlayerFolders = List.copyOf(playerFolders); vanillaUserCache = userCache;
    }
    PersistenceService(Path folder) {
        this(folder, settings -> settings.storageType() == PluginSettings.StorageType.MYSQL
            ? new MysqlPlaytimeStorage(settings.mysqlSettings()) : new LocalPlaytimeStorage(folder.resolve("data").toFile()));
    }
    PersistenceService(Path folder, Function<PluginSettings, PlaytimeStorage> factory) {
        this.folder = folder.toAbsolutePath().normalize(); this.factory = factory;
        journal = new RecoveryJournal(this.folder);
    }

    ConfigText readConfig() throws Exception {
        lockFolder();
        for (String file : List.of("config.yml", "lang.yml")) {
            Path path = folder.resolve(file);
            if (!Files.exists(path)) {
                try (var stream = PersistenceService.class.getResourceAsStream("/" + file)) {
                    if (stream == null) throw new IllegalStateException("Missing bundled " + file);
                    YamlFiles.writeText(path, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return new ConfigText(YamlFiles.read(folder.resolve("config.yml")), YamlFiles.read(folder.resolve("lang.yml")));
    }

    Prepared prepare(PluginSettings settings) throws Exception {
        lockFolder();
        if (candidate != null) { candidate.storage().close(); candidate = null; }
        PlaytimeStorage next = factory.apply(settings);
        String key = settings.storageType() == PluginSettings.StorageType.LOCAL ? "local"
            : settings.mysqlSettings().host() + ":" + settings.mysqlSettings().port() + "/"
                + settings.mysqlSettings().database() + "/" + settings.mysqlSettings().tablePrefix();
        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        try {
            next.initialize();
            RecoveryJournal.Checkpoint recovery = journal.read(id).get();
            if (recovery != null) {
                for (StorageMutation mutation : recovery.batch().mutations()) next.apply(mutation);
                applyFiles(recovery.batch().files(), recovery.generation());
            }
            StorageView view = next.loadView(); // Verify the durable result before deleting recovery state.
            if (recovery != null) journal.acknowledge(recovery).get();
            vanillaNames = loadVanillaNames();
            candidate = new Prepared(next, id, enrich(view));
            return candidate;
        } catch (Exception failure) {
            try { next.close(); } catch (Exception close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    void activate(Prepared prepared) throws Exception {
        if (prepared != candidate) throw new IllegalStateException("Obsolete storage candidate");
        PlaytimeStorage previous = storage;
        storage = prepared.storage(); identity = prepared.identity(); published = prepared.view(); candidate = null;
        if (previous != null) previous.close();
    }

    /** Safe to call on the main thread: only bounded queue admission, no I/O or waiting. */
    CompletableFuture<RecoveryJournal.Checkpoint> stage(String backend, Batch batch) { return journal.checkpoint(backend, batch); }

    Update persist(Batch batch) throws Exception { return persist(batch, stage(identity, batch)); }
    Update persist(Batch batch, CompletableFuture<RecoveryJournal.Checkpoint> staged) throws Exception {
        RecoveryJournal.Checkpoint checkpoint = staged.get();
        if (!batch.empty() && storage == null) throw new IllegalStateException("Storage not ready");
        for (StorageMutation mutation : batch.mutations()) storage.apply(mutation);
        if (checkpoint != null) applyFiles(batch.files(), checkpoint.generation());
        StorageView view = storage == null ? StorageView.empty() : enrich(storage.loadView());
        // A retry may checkpoint newer queued operations as well as the SQL batch being resolved.
        if (checkpoint == null || checkpoint.batch().mutations().equals(batch.mutations()))
            journal.acknowledge(checkpoint).get();
        return update(view);
    }
    Update refresh() throws Exception { return update(enrich(storage.loadView())); }

    private Update update(StorageView view) {
        Set<UUID> changed = new HashSet<>();
        Set<UUID> keys = new HashSet<>(published.players().keySet()); keys.addAll(view.players().keySet());
        keys.addAll(published.clocks().keySet()); keys.addAll(view.clocks().keySet());
        for (UUID uuid : keys) {
            if (published.resetMillis() != view.resetMillis()
                    || !java.util.Objects.equals(published.players().get(uuid), view.players().get(uuid))
                    || !java.util.Objects.equals(published.clocks().get(uuid), view.clocks().get(uuid))) changed.add(uuid);
        }
        published = view;
        return new Update(view, changed);
    }

    private StorageView enrich(StorageView view) {
        if (vanillaNames.isEmpty()) return view;
        Map<UUID, PlayerData> players = new HashMap<>(view.players());
        vanillaNames.forEach((uuid, name) -> players.putIfAbsent(uuid, PlayerData.empty(uuid, name)));
        return new StorageView(players, view.resetMillis(), view.clocks());
    }

    Map<String, Map<String, Object>> loadEvents() throws Exception {
        Path events = folder.resolve("events"); Files.createDirectories(events); Files.createDirectories(folder.resolve("event-logs"));
        Map<String, Map<String, Object>> result = new HashMap<>();
        try (var files = Files.list(events)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".yml")).toList())
                result.put(file.getFileName().toString(), YamlFiles.freeze(YamlFiles.read(file)));
        }
        return Map.copyOf(result);
    }

    private void lockFolder() throws Exception {
        if (folderLock != null) return;
        Files.createDirectories(folder);
        FileChannel channel = FileChannel.open(folder.resolve(".persistence.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            folderLock = channel.tryLock();
            if (folderLock == null) throw new IllegalStateException("Another hPlaytime writer is still using " + folder);
            lockChannel = channel;
        } catch (Exception failure) { channel.close(); throw failure; }
    }

    private Map<UUID, String> loadVanillaNames() {
        Map<UUID, String> names = new HashMap<>();
        if (vanillaUserCache == null || !Files.isRegularFile(vanillaUserCache)) return names;
        try (var reader = Files.newBufferedReader(vanillaUserCache)) {
            for (var value : JsonParser.parseReader(reader).getAsJsonArray()) {
                try {
                    var entry = value.getAsJsonObject(); UUID uuid = UUID.fromString(entry.get("uuid").getAsString());
                    for (Path directory : vanillaPlayerFolders) {
                        if (Files.isRegularFile(directory.resolve(uuid + ".dat"))) { names.put(uuid, entry.get("name").getAsString()); break; }
                    }
                } catch (RuntimeException malformedEntry) { /* Ignore unrelated malformed vanilla entries. */ }
            }
        } catch (Exception malformedCache) {
            java.util.logging.Logger.getLogger("hPlaytime").warning("Could not index vanilla usercache.json: " + malformedCache.getMessage());
        }
        return Map.copyOf(names);
    }

    private void applyFiles(Map<String, FileUpdate> updates, long generation) throws Exception {
        if (!versionsLoaded) {
            YamlFiles.read(folder.resolve(".file-versions.yml")).forEach((path, value) -> fileVersions.put(path, ((Number) value).longValue()));
            versionsLoaded = true;
        }
        for (FileUpdate update : updates.values()) {
            boolean eventState = update.path().startsWith("events/");
            if (eventState && generation < fileVersions.getOrDefault(update.path(), 0L)) continue;
            Path path = folder.resolve(update.path()).normalize();
            if (!path.startsWith(folder.resolve("events")) && !path.startsWith(folder.resolve("event-logs")))
                throw new IllegalArgumentException("Invalid persistence path: " + path);
            if (update.deleted()) Files.deleteIfExists(path); else YamlFiles.write(path, update.data());
            if (eventState) { fileVersions.put(update.path(), generation); versionsDirty = true; }
        }
        if (versionsDirty) {
            Map<String, Object> versions = new HashMap<>(fileVersions);
            YamlFiles.write(folder.resolve(".file-versions.yml"), versions); versionsDirty = false;
        }
    }

    static Map<String, Object> encode(Batch batch) {
        return Map.of("mutations", batch.mutations().stream().map(StorageMutation::encode).toList(),
            "files", batch.files().values().stream().map(update ->
                Map.of("path", update.path(), "deleted", update.deleted(), "data", update.data())).toList());
    }
    @SuppressWarnings("unchecked") static Batch decode(Map<String, Object> data) {
        List<StorageMutation> mutations = ((List<?>) data.getOrDefault("mutations", List.of())).stream()
            .map(value -> StorageMutation.decode((Map<String, Object>) value)).toList();
        Map<String, FileUpdate> files = new HashMap<>();
        for (Object value : (List<?>) data.getOrDefault("files", List.of())) {
            Map<String, Object> file = (Map<String, Object>) value;
            FileUpdate update = new FileUpdate((String) file.get("path"), Boolean.TRUE.equals(file.get("deleted")), (Map<String, Object>) file.get("data"));
            files.put(update.path(), update);
        }
        return new Batch(mutations, files);
    }

    @Override public void close() throws Exception {
        try { if (candidate != null) candidate.storage().close(); }
        finally {
            try { if (storage != null) storage.close(); }
            finally {
                try { journal.shutdown(Duration.ofSeconds(10)); }
                finally {
                    try { if (folderLock != null) folderLock.release(); }
                    finally { if (lockChannel != null) lockChannel.close(); }
                    folderLock = null; lockChannel = null;
                }
            }
        }
    }
}
