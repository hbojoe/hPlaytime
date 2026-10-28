package com.hboj.hPlaytime;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Server-thread event accounting. Disk operations are represented by immutable file snapshots. */
public final class EventManager {
    private static final Pattern EVENT_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern DURATION_PART_PATTERN = Pattern.compile("(\\d+)([dhms])", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault());
    private static final int MAX_PENDING_LOGS = 1024;
    private final HPlaytime plugin;
    private final Lang lang;
    private final Map<String, PlaytimeEvent> events = new HashMap<>();
    private final Map<String, PlaytimeEvent> active = new HashMap<>();
    private final Map<String, Long> dirty = new HashMap<>();
    private final Map<String, PersistenceService.FileUpdate> logs = new HashMap<>();
    private long revision;
    private final LongSupplier clock;

    record Pending(Map<String, PersistenceService.FileUpdate> files, Map<String, Long> versions) {
        Pending { files = Map.copyOf(files); versions = Map.copyOf(versions); }
    }

    public EventManager(HPlaytime plugin, Lang lang) { this(plugin, lang, System::currentTimeMillis); }
    EventManager(HPlaytime plugin, Lang lang, LongSupplier clock) { this.plugin = plugin; this.lang = lang; this.clock = clock; }

    @SuppressWarnings("unchecked")
    void load(Map<String, Map<String, Object>> stored) {
        for (Map<String, Object> data : stored.values()) {
            String name = normalizeName((String) data.get("name"));
            if (!isValidName(name)) throw new IllegalArgumentException("Invalid stored event name: " + name);
            Map<UUID, EventPlayer> players = new HashMap<>();
            ((Map<String, Object>) data.getOrDefault("players", Map.of())).forEach((id, value) -> {
                Map<String, Object> entry = (Map<String, Object>) value;
                UUID uuid = UUID.fromString(id);
                players.put(uuid, new EventPlayer(uuid, entry.getOrDefault("name", id).toString(), YamlFiles.number(entry, "millis")));
            });
            PlaytimeEvent event = new PlaytimeEvent(name, YamlFiles.number(data, "duration-millis"),
                Boolean.TRUE.equals(data.get("active")), YamlFiles.number(data, "started-at-millis"),
                YamlFiles.number(data, "ends-at-millis"), players);
            events.put(name, event);
            if (event.active) active.put(name, event);
        }
    }

    boolean canMutate() { return logs.size() < MAX_PENDING_LOGS; }
    boolean dirty() { return !dirty.isEmpty() || !logs.isEmpty(); }

    public CreateResult create(String input, String durationInput) {
        String name = normalizeName(input);
        if (!isValidName(name)) return CreateResult.INVALID_NAME;
        Optional<Long> duration = parseDurationMillis(durationInput);
        if (duration.isEmpty()) return CreateResult.INVALID_DURATION;
        if (events.containsKey(name)) return CreateResult.ALREADY_EXISTS;
        events.put(name, new PlaytimeEvent(name, duration.get(), false, 0, 0, new HashMap<>()));
        mark(name);
        return CreateResult.CREATED;
    }

    public ActionResult start(String input) {
        PlaytimeEvent event = events.get(normalizeName(input));
        if (event == null) return ActionResult.NOT_FOUND;
        if (event.active) return ActionResult.ALREADY_ACTIVE;
        long now = clock.getAsLong();
        event.active = true;
        event.startedAtMillis = now;
        event.endsAtMillis = now + event.durationMillis;
        event.players.clear();
        active.put(event.name, event);
        mark(event.name);
        return ActionResult.OK;
    }

    public ActionResult end(String input) {
        PlaytimeEvent event = events.get(normalizeName(input));
        if (event == null) return ActionResult.NOT_FOUND;
        if (!event.active) return ActionResult.NOT_ACTIVE;
        finishEvent(event, "manual-end", true);
        return ActionResult.OK;
    }

    public ActionResult delete(String input) {
        PlaytimeEvent event = events.remove(normalizeName(input));
        if (event == null) return ActionResult.NOT_FOUND;
        active.remove(event.name);
        writeLog(event, "deleted", clock.getAsLong());
        mark(event.name); // A tombstone; an earlier in-flight write cannot resurrect it.
        return ActionResult.OK;
    }

    public void addPlaytime(UUID uuid, String name, long fromMillis, long toMillis) {
        if (toMillis <= fromMillis) return;
        for (PlaytimeEvent event : active.values()) {
            long start = Math.max(fromMillis, event.startedAtMillis);
            long end = Math.min(toMillis, event.endsAtMillis);
            if (end <= start) continue;
            EventPlayer player = event.players.computeIfAbsent(uuid, ignored -> new EventPlayer(uuid, name, 0));
            player.name = name;
            player.millis += end - start;
            mark(event.name);
        }
    }

    /** Called after all players have been accounted; never end an event halfway through a flush. */
    public void tick() {
        long now = clock.getAsLong();
        for (PlaytimeEvent event : new ArrayList<>(active.values())) {
            if (!canMutate()) break; // Bounded log backlog: defer completion until persistence catches up.
            if (now >= event.endsAtMillis) finishEvent(event, "expired", true);
        }
    }

    public List<String> eventNames() { return events.keySet().stream().sorted().toList(); }
    public Optional<PlaytimeEvent> event(String name) { return Optional.ofNullable(events.get(normalizeName(name))); }

    private void mark(String name) { dirty.put(name, ++revision); }

    Pending pending() {
        Map<String, PersistenceService.FileUpdate> files = new HashMap<>(logs);
        for (String name : dirty.keySet()) {
            PlaytimeEvent event = events.get(name);
            String path = "events/" + name + ".yml";
            files.put(path, new PersistenceService.FileUpdate(path, event == null, event == null ? Map.of() : eventData(event)));
        }
        return new Pending(files, dirty);
    }

    void acknowledge(Pending saved) {
        saved.versions.forEach((name, version) -> dirty.remove(name, version));
        saved.files.forEach((path, update) -> logs.remove(path, update));
    }

    private Map<String, Object> eventData(PlaytimeEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", event.name);
        data.put("duration-millis", event.durationMillis);
        data.put("active", event.active);
        data.put("started-at-millis", event.startedAtMillis);
        data.put("ends-at-millis", event.endsAtMillis);
        Map<String, Object> players = new LinkedHashMap<>();
        event.players.values().forEach(player -> players.put(player.uuid.toString(), Map.of("name", player.name, "millis", player.millis)));
        data.put("players", players);
        return data;
    }

    private void finishEvent(PlaytimeEvent event, String reason, boolean broadcast) {
        event.active = false;
        active.remove(event.name);
        writeLog(event, reason, clock.getAsLong());
        mark(event.name);
        plugin.requestSave();
        if (!broadcast) return;
        Optional<EventPlayer> winner = event.highestPlayer();
        if (winner.isPresent()) {
            lang.broadcast("event-ended", Map.of("event", event.name, "player", winner.get().name,
                "time", TimeFormatter.format(winner.get().millis, plugin.playtimeManager().timeFormatterSettings())));
        } else lang.broadcast("event-ended-no-players", Map.of("event", event.name));
    }

    private void writeLog(PlaytimeEvent event, String reason, long now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", event.name); data.put("reason", reason);
        data.put("duration-millis", event.durationMillis);
        data.put("started-at-millis", event.startedAtMillis);
        data.put("ended-at-millis", now);
        event.highestPlayer().ifPresent(winner -> data.put("winner",
            Map.of("uuid", winner.uuid.toString(), "name", winner.name, "millis", winner.millis)));
        Map<String, Object> results = new LinkedHashMap<>();
        int rank = 0;
        for (EventPlayer player : event.players.values().stream()
                .sorted(Comparator.comparingLong(EventPlayer::millis).reversed()).toList()) {
            results.put(Integer.toString(++rank), Map.of("uuid", player.uuid.toString(), "name", player.name, "millis", player.millis));
        }
        data.put("results", results);
        String path = "event-logs/" + event.name + "-" + LOG_TIME.format(Instant.ofEpochMilli(now)) + "-" + UUID.randomUUID() + ".yml";
        logs.put(path, new PersistenceService.FileUpdate(path, false, data));
    }

    private String normalizeName(String name) { return name == null ? "" : name.toLowerCase(Locale.ROOT); }
    private boolean isValidName(String name) { return EVENT_NAME_PATTERN.matcher(name).matches(); }
    private Optional<Long> parseDurationMillis(String input) {
        Matcher matcher = DURATION_PART_PATTERN.matcher(input);
        long seconds = 0;
        int matched = 0;
        try {
            while (matcher.find()) {
                long amount = Long.parseLong(matcher.group(1));
                long unit = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                    case "d" -> 86400; case "h" -> 3600; case "m" -> 60; default -> 1;
                };
                matched += matcher.group(0).length();
                seconds = Math.addExact(seconds, Math.multiplyExact(amount, unit));
            }
            long millis = Math.multiplyExact(seconds, 1000);
            return matched == input.length() && millis > 0 && millis <= Long.MAX_VALUE - clock.getAsLong()
                ? Optional.of(millis) : Optional.empty();
        } catch (ArithmeticException | NumberFormatException invalid) { return Optional.empty(); }
    }

    public enum CreateResult { CREATED, INVALID_NAME, INVALID_DURATION, ALREADY_EXISTS }
    public enum ActionResult { OK, NOT_FOUND, ALREADY_ACTIVE, NOT_ACTIVE }

    public static final class PlaytimeEvent {
        private final String name;
        private final long durationMillis;
        private boolean active;
        private long startedAtMillis;
        private long endsAtMillis;
        private final Map<UUID, EventPlayer> players;
        private PlaytimeEvent(String name, long duration, boolean active, long started, long ends, Map<UUID, EventPlayer> players) {
            this.name = name; this.durationMillis = duration; this.active = active;
            this.startedAtMillis = started; this.endsAtMillis = ends; this.players = players;
        }
        public String name() { return name; }
        public long durationMillis() { return durationMillis; }
        public boolean active() { return active; }
        public long startedAtMillis() { return startedAtMillis; }
        public long endsAtMillis() { return endsAtMillis; }
        public List<EventPlayer> players() { return players.values().stream().sorted(Comparator.comparing(EventPlayer::name)).toList(); }
        private Optional<EventPlayer> highestPlayer() { return players.values().stream().max(Comparator.comparingLong(EventPlayer::millis)); }
    }

    public static final class EventPlayer {
        private final UUID uuid;
        private String name;
        private long millis;
        private EventPlayer(UUID uuid, String name, long millis) { this.uuid = uuid; this.name = name; this.millis = millis; }
        public UUID uuid() { return uuid; }
        public String name() { return name; }
        public long millis() { return millis; }
    }
}
