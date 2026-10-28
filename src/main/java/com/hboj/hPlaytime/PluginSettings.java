package com.hboj.hPlaytime;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Map;

public final class PluginSettings {
    private final StorageType storageType;
    private final MysqlSettings mysqlSettings;
    private final ZoneId zoneId;
    private final DateTimeFormatter dailyFormatter;
    private final DateTimeFormatter monthlyFormatter;
    private final TimeFormatter.Settings timeFormatterSettings;
    private final boolean afkEnabled;
    private final long afkTimeoutMillis;
    private final Set<String> afkWorlds;
    private final long flushIntervalTicks;
    private final boolean lastSeenEnabled;
    private final DateTimeFormatter lastSeenFormatter;

    private PluginSettings(
        StorageType storageType,
        MysqlSettings mysqlSettings,
        ZoneId zoneId,
        DateTimeFormatter dailyFormatter,
        DateTimeFormatter monthlyFormatter,
        TimeFormatter.Settings timeFormatterSettings,
        boolean afkEnabled,
        long afkTimeoutMillis,
        Set<String> afkWorlds,
        long flushIntervalTicks,
        boolean lastSeenEnabled,
        DateTimeFormatter lastSeenFormatter
    ) {
        this.storageType = storageType;
        this.mysqlSettings = mysqlSettings;
        this.zoneId = zoneId;
        this.dailyFormatter = dailyFormatter;
        this.monthlyFormatter = monthlyFormatter;
        this.timeFormatterSettings = timeFormatterSettings;
        this.afkEnabled = afkEnabled;
        this.afkTimeoutMillis = afkTimeoutMillis;
        this.afkWorlds = afkWorlds;
        this.flushIntervalTicks = flushIntervalTicks;
        this.lastSeenEnabled = lastSeenEnabled;
        this.lastSeenFormatter = lastSeenFormatter;
    }

    public static PluginSettings load(Map<String, Object> values) {
        SettingsReader config = new SettingsReader(values);

        StorageType storageType = StorageType.from(config.getString("storage.type", "local"));
        MysqlSettings mysqlSettings = MysqlSettings.load(config.getConfigurationSection("storage.mysql"));

        ZoneId zoneId = loadZoneId(config.getString("date-format.timezone", "system"));
        DateTimeFormatter dailyFormatter = DateTimeFormatter.ofPattern(
            config.getString("date-format.daily-pattern", "yyyy-MM-dd"),
            Locale.US
        );
        DateTimeFormatter monthlyFormatter = DateTimeFormatter.ofPattern(
            config.getString("date-format.monthly-pattern", "yyyy-MM"),
            Locale.US
        );

        boolean afkEnabled = config.getBoolean("afk.enabled", true);
        long afkTimeoutMillis = Math.max(1L, Math.min(Integer.MAX_VALUE, config.getLong("afk.timeout-seconds", 300L))) * 1000L;
        Set<String> afkWorlds = config.getStringList("afk.worlds").stream()
            .map(world -> world.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

        long flushIntervalTicks = Math.max(10L, Math.min(Integer.MAX_VALUE, config.getLong("flush-interval-seconds", 60L)) * 20L);
        boolean lastSeenEnabled = config.getBoolean("last-seen.enabled", true);
        DateTimeFormatter lastSeenFormatter = DateTimeFormatter.ofPattern(
            config.getString("last-seen.pattern", "yyyy-MM-dd HH:mm:ss z"),
            Locale.US
        );

        return new PluginSettings(
            storageType,
            mysqlSettings,
            zoneId,
            dailyFormatter,
            monthlyFormatter,
            TimeFormatter.Settings.load(config.getConfigurationSection("time-format")),
            afkEnabled,
            afkTimeoutMillis,
            afkWorlds,
            flushIntervalTicks,
            lastSeenEnabled,
            lastSeenFormatter
        );
    }

    private static ZoneId loadZoneId(String configuredZone) {
        if (configuredZone == null || configuredZone.equalsIgnoreCase("system")) {
            return ZoneId.systemDefault();
        }

        try {
            return ZoneId.of(configuredZone);
        } catch (DateTimeException exception) {
            return ZoneId.systemDefault();
        }
    }

    public StorageType storageType() {
        return storageType;
    }

    public MysqlSettings mysqlSettings() {
        return mysqlSettings;
    }

    public ZoneId zoneId() {
        return zoneId;
    }

    public DateTimeFormatter dailyFormatter() {
        return dailyFormatter;
    }

    public DateTimeFormatter monthlyFormatter() {
        return monthlyFormatter;
    }

    public TimeFormatter.Settings timeFormatterSettings() {
        return timeFormatterSettings;
    }

    public boolean afkEnabled() {
        return afkEnabled;
    }

    public long afkTimeoutMillis() {
        return afkTimeoutMillis;
    }

    public boolean isAfkWorld(String worldName) {
        return afkWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    public long flushIntervalTicks() {
        return flushIntervalTicks;
    }

    public boolean lastSeenEnabled() {
        return lastSeenEnabled;
    }

    public DateTimeFormatter lastSeenFormatter() {
        return lastSeenFormatter;
    }

    public enum StorageType {
        LOCAL,
        MYSQL;

        public static StorageType from(String value) {
            if (value != null && value.equalsIgnoreCase("mysql")) {
                return MYSQL;
            }
            return LOCAL;
        }
    }

    private static int bounded(SettingsReader section, String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, section.getInt(key, fallback)));
    }

    public record MysqlSettings(
        String host,
        int port,
        String database,
        String username,
        String password,
        boolean useSsl,
        String tablePrefix,
        int maximumPoolSize, long connectionTimeoutMillis, long validationTimeoutMillis,
        int connectTimeoutMillis, int socketTimeoutMillis, int queryTimeoutSeconds
    ) {
        private static MysqlSettings load(SettingsReader section) {
            if (section == null) {
                return new MysqlSettings("localhost", 3306, "minecraft", "root", "", false, "hplaytime_", 2, 3000, 1000, 3000, 5000, 5);
            }

            return new MysqlSettings(
                section.getString("host", "localhost"),
                section.getInt("port", 3306),
                section.getString("database", "minecraft"),
                section.getString("username", "root"),
                section.getString("password", ""),
                section.getBoolean("use-ssl", false),
                section.getString("table-prefix", "hplaytime_"),
                bounded(section, "maximum-pool-size", 2, 1, 8),
                bounded(section, "connection-timeout-millis", 3000, 500, 30000),
                Math.min(bounded(section, "validation-timeout-millis", 1000, 250, 5000),
                    bounded(section, "connection-timeout-millis", 3000, 500, 30000) - 1),
                bounded(section, "connect-timeout-millis", 3000, 250, 30000),
                bounded(section, "socket-timeout-millis", 5000, 250, 30000),
                bounded(section, "query-timeout-seconds", 5, 1, 30)
            );
        }
    }
}
