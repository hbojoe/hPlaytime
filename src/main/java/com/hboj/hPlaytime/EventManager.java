package com.hboj.hPlaytime;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class EventManager {
    private static final Pattern EVENT_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern DURATION_PART_PATTERN = Pattern.compile("(\\d+)([dhms])", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter LOG_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault());

    private final HPlaytime plugin;
    private final Lang lang;
    private final File eventsFolder;
    private final File logsFolder;
    private final Map<String, PlaytimeEvent> events = new HashMap<>();

    public EventManager(HPlaytime plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;
        this.eventsFolder = new File(plugin.getDataFolder(), "events");
        this.logsFolder = new File(plugin.getDataFolder(), "event-logs");
        initializeFolders();
        loadEvents();
    }

    public CreateResult create(String name, String durationInput) {
        String normalizedName = normalizeName(name);
        if (!isValidName(normalizedName)) {
            return CreateResult.INVALID_NAME;
        }

        Optional<Long> durationMillis = parseDurationMillis(durationInput);
        if (durationMillis.isEmpty()) {
            return CreateResult.INVALID_DURATION;
        }

        if (events.containsKey(normalizedName)) {
            return CreateResult.ALREADY_EXISTS;
        }

        PlaytimeEvent event = new PlaytimeEvent(normalizedName, durationMillis.get(), false, 0L, 0L, new HashMap<>());
        events.put(normalizedName, event);
        saveEvent(event);
        return CreateResult.CREATED;
    }

    public ActionResult start(String name) {
        PlaytimeEvent event = events.get(normalizeName(name));
        if (event == null) {
            return ActionResult.NOT_FOUND;
        }
        if (event.active()) {
            return ActionResult.ALREADY_ACTIVE;
        }

        long now = System.currentTimeMillis();
        event.setActive(true);
        event.setStartedAtMillis(now);
        event.setEndsAtMillis(now + event.durationMillis());
        event.clearPlayers();
        saveEvent(event);
        return ActionResult.OK;
    }

    public ActionResult end(String name) {
        PlaytimeEvent event = events.get(normalizeName(name));
        if (event == null) {
            return ActionResult.NOT_FOUND;
        }
        if (!event.active()) {
            return ActionResult.NOT_ACTIVE;
        }

        finishEvent(event, "manual-end", true);
        return ActionResult.OK;
    }

    public ActionResult delete(String name) {
        PlaytimeEvent event = events.remove(normalizeName(name));
        if (event == null) {
            return ActionResult.NOT_FOUND;
        }

        writeLog(event, "deleted", System.currentTimeMillis());
        File file = eventFile(event.name());
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("Could not delete event file: " + file);
        }
        return ActionResult.OK;
    }

    public void addPlaytime(UUID uuid, String playerName, long fromMillis, long toMillis) {
        if (toMillis <= fromMillis) {
            return;
        }

        long now = System.currentTimeMillis();
        for (PlaytimeEvent event : events.values()) {
            if (!event.active()) {
                continue;
            }

            long overlapStart = Math.max(fromMillis, event.startedAtMillis());
            long overlapEnd = Math.min(toMillis, event.endsAtMillis());
            if (overlapEnd > overlapStart) {
                event.addPlaytime(uuid, playerName, overlapEnd - overlapStart);
                saveEvent(event);
            }

            if (now >= event.endsAtMillis()) {
                finishEvent(event, "expired", true);
            }
        }
    }

    public void tick() {
        long now = System.currentTimeMillis();
        for (PlaytimeEvent event : new ArrayList<>(events.values())) {
            if (event.active() && now >= event.endsAtMillis()) {
                finishEvent(event, "expired", true);
            }
        }
    }

    public List<String> eventNames() {
        return events.keySet().stream().sorted().toList();
    }

    public Optional<PlaytimeEvent> event(String name) {
        return Optional.ofNullable(events.get(normalizeName(name)));
    }

    private void finishEvent(PlaytimeEvent event, String reason, boolean broadcast) {
        event.setActive(false);
        writeLog(event, reason, System.currentTimeMillis());
        saveEvent(event);

        if (broadcast) {
            Optional<EventPlayer> winner = event.highestPlayer();
            if (winner.isPresent()) {
                TimeFormatter.Settings timeSettings = plugin.playtimeManager() == null
                    ? TimeFormatter.Settings.defaults()
                    : plugin.playtimeManager().timeFormatterSettings();
                lang.broadcast("event-ended", Map.of(
                    "event", event.name(),
                    "player", winner.get().name(),
                    "time", TimeFormatter.format(winner.get().millis(), timeSettings)
                ));
            } else {
                lang.broadcast("event-ended-no-players", Map.of("event", event.name()));
            }
        }
    }

    private void loadEvents() {
        File[] files = eventsFolder.listFiles((directory, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
            String name = data.getString("name");
            if (name == null) {
                continue;
            }

            Map<UUID, EventPlayer> players = new HashMap<>();
            ConfigurationSection playersSection = data.getConfigurationSection("players");
            if (playersSection != null) {
                for (String uuidInput : playersSection.getKeys(false)) {
                    UUID uuid = UUID.fromString(uuidInput);
                    players.put(uuid, new EventPlayer(
                        uuid,
                        playersSection.getString(uuidInput + ".name", uuidInput),
                        playersSection.getLong(uuidInput + ".millis", 0L)
                    ));
                }
            }

            events.put(normalizeName(name), new PlaytimeEvent(
                normalizeName(name),
                data.getLong("duration-millis", 0L),
                data.getBoolean("active", false),
                data.getLong("started-at-millis", 0L),
                data.getLong("ends-at-millis", 0L),
                players
            ));
        }

        tick();
    }

    private void saveEvent(PlaytimeEvent event) {
        YamlConfiguration data = new YamlConfiguration();
        data.set("name", event.name());
        data.set("duration-millis", event.durationMillis());
        data.set("active", event.active());
        data.set("started-at-millis", event.startedAtMillis());
        data.set("ends-at-millis", event.endsAtMillis());
        for (EventPlayer player : event.players()) {
            String base = "players." + player.uuid();
            data.set(base + ".name", player.name());
            data.set(base + ".millis", player.millis());
        }

        try {
            data.save(eventFile(event.name()));
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save event " + event.name() + ": " + exception.getMessage());
        }
    }

    private void writeLog(PlaytimeEvent event, String reason, long loggedAtMillis) {
        YamlConfiguration data = new YamlConfiguration();
        data.set("name", event.name());
        data.set("reason", reason);
        data.set("duration-millis", event.durationMillis());
        data.set("started-at-millis", event.startedAtMillis());
        data.set("ended-at-millis", loggedAtMillis);

        Optional<EventPlayer> winner = event.highestPlayer();
        winner.ifPresent(player -> {
            data.set("winner.uuid", player.uuid().toString());
            data.set("winner.name", player.name());
            data.set("winner.millis", player.millis());
        });

        int index = 1;
        for (EventPlayer player : event.players().stream()
            .sorted(Comparator.comparingLong(EventPlayer::millis).reversed())
            .toList()) {
            String base = "results." + index;
            data.set(base + ".uuid", player.uuid().toString());
            data.set(base + ".name", player.name());
            data.set(base + ".millis", player.millis());
            index++;
        }

        File file = new File(logsFolder, event.name() + "-" + LOG_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(loggedAtMillis)) + ".yml");
        try {
            data.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not write event log " + file + ": " + exception.getMessage());
        }
    }

    private void initializeFolders() {
        if (!eventsFolder.exists() && !eventsFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create event folder: " + eventsFolder);
        }
        if (!logsFolder.exists() && !logsFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create event log folder: " + logsFolder);
        }
    }

    private File eventFile(String name) {
        return new File(eventsFolder, name + ".yml");
    }

    private String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private boolean isValidName(String name) {
        return EVENT_NAME_PATTERN.matcher(name).matches();
    }

    private Optional<Long> parseDurationMillis(String input) {
        Matcher matcher = DURATION_PART_PATTERN.matcher(input);
        long totalSeconds = 0L;
        int matchedCharacters = 0;

        while (matcher.find()) {
            long amount = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            matchedCharacters += matcher.group(0).length();
            totalSeconds += switch (unit) {
                case "d" -> amount * 86_400L;
                case "h" -> amount * 3_600L;
                case "m" -> amount * 60L;
                case "s" -> amount;
                default -> 0L;
            };
        }

        if (matchedCharacters != input.length() || totalSeconds <= 0L) {
            return Optional.empty();
        }
        return Optional.of(totalSeconds * 1000L);
    }

    public enum CreateResult {
        CREATED,
        INVALID_NAME,
        INVALID_DURATION,
        ALREADY_EXISTS
    }

    public enum ActionResult {
        OK,
        NOT_FOUND,
        ALREADY_ACTIVE,
        NOT_ACTIVE
    }

    public static final class PlaytimeEvent {
        private final String name;
        private final long durationMillis;
        private final Map<UUID, EventPlayer> players;
        private boolean active;
        private long startedAtMillis;
        private long endsAtMillis;

        private PlaytimeEvent(
            String name,
            long durationMillis,
            boolean active,
            long startedAtMillis,
            long endsAtMillis,
            Map<UUID, EventPlayer> players
        ) {
            this.name = name;
            this.durationMillis = durationMillis;
            this.active = active;
            this.startedAtMillis = startedAtMillis;
            this.endsAtMillis = endsAtMillis;
            this.players = players;
        }

        public String name() {
            return name;
        }

        public long durationMillis() {
            return durationMillis;
        }

        public boolean active() {
            return active;
        }

        private void setActive(boolean active) {
            this.active = active;
        }

        public long startedAtMillis() {
            return startedAtMillis;
        }

        private void setStartedAtMillis(long startedAtMillis) {
            this.startedAtMillis = startedAtMillis;
        }

        public long endsAtMillis() {
            return endsAtMillis;
        }

        private void setEndsAtMillis(long endsAtMillis) {
            this.endsAtMillis = endsAtMillis;
        }

        public List<EventPlayer> players() {
            return players.values().stream()
                .sorted(Comparator.comparing(EventPlayer::name))
                .collect(Collectors.toCollection(ArrayList::new));
        }

        private void clearPlayers() {
            players.clear();
        }

        private void addPlaytime(UUID uuid, String playerName, long millis) {
            EventPlayer player = players.computeIfAbsent(uuid, key -> new EventPlayer(uuid, playerName, 0L));
            player.setName(playerName);
            player.addMillis(millis);
        }

        private Optional<EventPlayer> highestPlayer() {
            return players.values().stream().max(Comparator.comparingLong(EventPlayer::millis));
        }
    }

    public static final class EventPlayer {
        private final UUID uuid;
        private String name;
        private long millis;

        private EventPlayer(UUID uuid, String name, long millis) {
            this.uuid = uuid;
            this.name = name;
            this.millis = millis;
        }

        public UUID uuid() {
            return uuid;
        }

        public String name() {
            return name;
        }

        private void setName(String name) {
            this.name = name;
        }

        public long millis() {
            return millis;
        }

        private void addMillis(long millis) {
            this.millis += millis;
        }
    }
}
