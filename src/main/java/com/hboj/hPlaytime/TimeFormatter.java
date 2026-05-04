package com.hboj.hPlaytime;

import org.bukkit.configuration.ConfigurationSection;

public final class TimeFormatter {
    private TimeFormatter() {
    }

    public static String format(long millis, Settings settings) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (totalSeconds == 0L) {
            return settings.zero();
        }

        if (settings.style().equalsIgnoreCase("long")) {
            return formatLong(days, hours, minutes, seconds, settings);
        }

        return formatCompact(days, hours, minutes, seconds, settings);
    }

    private static String formatCompact(long days, long hours, long minutes, long seconds, Settings settings) {
        StringBuilder builder = new StringBuilder();
        if (days > 0L) {
            append(builder, days, settings.compactDay());
            append(builder, hours, settings.compactHour());
            append(builder, minutes, settings.compactMinute());
            return builder.toString();
        }
        if (hours > 0L) {
            append(builder, hours, settings.compactHour());
            append(builder, minutes, settings.compactMinute());
            if (settings.showSeconds() && minutes == 0L) {
                append(builder, seconds, settings.compactSecond());
            }
            return builder.toString();
        }
        if (minutes > 0L) {
            append(builder, minutes, settings.compactMinute());
            if (settings.showSeconds()) {
                append(builder, seconds, settings.compactSecond());
            }
            return builder.toString();
        }
        append(builder, seconds, settings.compactSecond());
        return builder.toString();
    }

    private static String formatLong(long days, long hours, long minutes, long seconds, Settings settings) {
        StringBuilder builder = new StringBuilder();
        if (days > 0L) {
            append(builder, days, label(days, settings.longDay(), settings.longDays()));
            append(builder, hours, label(hours, settings.longHour(), settings.longHours()));
            append(builder, minutes, label(minutes, settings.longMinute(), settings.longMinutes()));
            return builder.toString();
        }
        if (hours > 0L) {
            append(builder, hours, label(hours, settings.longHour(), settings.longHours()));
            append(builder, minutes, label(minutes, settings.longMinute(), settings.longMinutes()));
            if (settings.showSeconds() && minutes == 0L) {
                append(builder, seconds, label(seconds, settings.longSecond(), settings.longSeconds()));
            }
            return builder.toString();
        }
        if (minutes > 0L) {
            append(builder, minutes, label(minutes, settings.longMinute(), settings.longMinutes()));
            if (settings.showSeconds()) {
                append(builder, seconds, label(seconds, settings.longSecond(), settings.longSeconds()));
            }
            return builder.toString();
        }
        append(builder, seconds, label(seconds, settings.longSecond(), settings.longSeconds()));
        return builder.toString();
    }

    private static void append(StringBuilder builder, long value, String label) {
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(value).append(label.length() == 1 ? "" : " ").append(label);
    }

    private static String label(long value, String singular, String plural) {
        return value == 1L ? singular : plural;
    }

    public record Settings(
        String style,
        boolean showSeconds,
        String zero,
        String compactDay,
        String compactHour,
        String compactMinute,
        String compactSecond,
        String longDay,
        String longDays,
        String longHour,
        String longHours,
        String longMinute,
        String longMinutes,
        String longSecond,
        String longSeconds
    ) {
        public static Settings load(ConfigurationSection section) {
            if (section == null) {
                return defaults();
            }

            return new Settings(
                section.getString("style", "compact"),
                section.getBoolean("show-seconds", true),
                section.getString("zero", "0s"),
                section.getString("compact.day", "d"),
                section.getString("compact.hour", "h"),
                section.getString("compact.minute", "m"),
                section.getString("compact.second", "s"),
                section.getString("long.day", "day"),
                section.getString("long.days", "days"),
                section.getString("long.hour", "hour"),
                section.getString("long.hours", "hours"),
                section.getString("long.minute", "minute"),
                section.getString("long.minutes", "minutes"),
                section.getString("long.second", "second"),
                section.getString("long.seconds", "seconds")
            );
        }

        public static Settings defaults() {
            return new Settings(
                "compact",
                true,
                "0s",
                "d",
                "h",
                "m",
                "s",
                "day",
                "days",
                "hour",
                "hours",
                "minute",
                "minutes",
                "second",
                "seconds"
            );
        }
    }
}
