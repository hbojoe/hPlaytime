package com.hboj.hPlaytime;

import java.util.List;
import java.util.Map;

/** Small typed view over the worker-parsed YAML tree, independent of the server's YAML version. */
final class SettingsReader {
    private final Map<String, Object> data;
    SettingsReader(Map<String, Object> data) { this.data = data; }
    private Object value(String path) {
        Object current = data;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }
    String getString(String path, String fallback) { Object value = value(path); return value == null ? fallback : value.toString(); }
    boolean getBoolean(String path, boolean fallback) { return value(path) instanceof Boolean bool ? bool : fallback; }
    long getLong(String path, long fallback) { return value(path) instanceof Number number ? number.longValue() : fallback; }
    int getInt(String path, int fallback) { long value = getLong(path, fallback); return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value)); }
    List<String> getStringList(String path) {
        return value(path) instanceof List<?> list ? list.stream()
            .filter(item -> item instanceof String || item instanceof Number || item instanceof Boolean)
            .map(Object::toString).toList() : List.of();
    }
    @SuppressWarnings("unchecked") SettingsReader getConfigurationSection(String path) {
        return value(path) instanceof Map<?, ?> map ? new SettingsReader((Map<String, Object>) map) : null;
    }
}
