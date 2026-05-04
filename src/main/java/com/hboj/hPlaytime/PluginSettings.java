package com.hboj.hPlaytime;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.configuration.ConfigurationSection;

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

    public static PluginSettings load(HPlaytime plugin) {
        plugin.reloadConfig();

        StorageType storageType = StorageType.from(plugin.getConfig().getString("storage.type", "local"));
        MysqlSettings mysqlSettings = MysqlSettings.load(plugin.getConfig().getConfigurationSection("storage.mysql"));

        ZoneId zoneId = loadZoneId(plugin.getConfig().getString("date-format.timezone", "system"));
        DateTimeFormatter dailyFormatter = DateTimeFormatter.ofPattern(
            plugin.getConfig().getString("date-format.daily-pattern", "yyyy-MM-dd"),
            Locale.US
        );
        DateTimeFormatter monthlyFormatter = DateTimeFormatter.ofPattern(
            plugin.getConfig().getString("date-format.monthly-pattern", "yyyy-MM"),
            Locale.US
        );

        boolean afkEnabled = plugin.getConfig().getBoolean("afk.enabled", true);
        long afkTimeoutMillis = Math.max(1L, plugin.getConfig().getLong("afk.timeout-seconds", 300L)) * 1000L;
        Set<String> afkWorlds = plugin.getConfig().getStringList("afk.worlds").stream()
            .map(world -> world.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

        long flushIntervalTicks = Math.max(10L, plugin.getConfig().getLong("flush-interval-seconds", 60L) * 20L);
        boolean lastSeenEnabled = plugin.getConfig().getBoolean("last-seen.enabled", true);
        DateTimeFormatter lastSeenFormatter = DateTimeFormatter.ofPattern(
            plugin.getConfig().getString("last-seen.pattern", "yyyy-MM-dd HH:mm:ss z"),
            Locale.US
        );

        return new PluginSettings(
            storageType,
            mysqlSettings,
            zoneId,
            dailyFormatter,
            monthlyFormatter,
            TimeFormatter.Settings.load(plugin.getConfig().getConfigurationSection("time-format")),
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

    public record MysqlSettings(
        String host,
        int port,
        String database,
        String username,
        String password,
        boolean useSsl,
        String tablePrefix
    ) {
        private static MysqlSettings load(ConfigurationSection section) {
            if (section == null) {
                return new MysqlSettings("localhost", 3306, "minecraft", "root", "", false, "hplaytime_");
            }

            return new MysqlSettings(
                section.getString("host", "localhost"),
                section.getInt("port", 3306),
                section.getString("database", "minecraft"),
                section.getString("username", "root"),
                section.getString("password", ""),
                section.getBoolean("use-ssl", false),
                section.getString("table-prefix", "hplaytime_")
            );
        }
    }
}
