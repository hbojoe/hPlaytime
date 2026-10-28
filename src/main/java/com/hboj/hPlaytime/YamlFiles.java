package com.hboj.hPlaytime;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/** Pure Java YAML and atomic replacement; used only by the I/O worker. */
final class YamlFiles {
    static final int MAX_CODE_POINTS = 64 * 1024 * 1024;
    private YamlFiles() {}

    static Map<String, Object> read(Path file) throws IOException {
        return read(file, MAX_CODE_POINTS);
    }
    static Map<String, Object> read(Path file, int maximum) throws IOException {
        if (!Files.exists(file)) return Map.of();
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(maximum);
        Object data;
        try (var reader = new StringReader(readBounded(file, maximum))) {
            DumperOptions dump = new DumperOptions();
            Resolver resolver = new Resolver() {
                @Override public Tag resolve(NodeId kind, String value, boolean implicit) {
                    Tag tag = super.resolve(kind, value, implicit);
                    // Bukkit treats period keys as strings. Do not turn ISO date keys into java.util.Date.
                    return tag.equals(Tag.TIMESTAMP) ? Tag.STR : tag;
                }
            };
            data = new Yaml(new SafeConstructor(options), new Representer(dump), dump, options, resolver).load(reader);
        }
        if (data == null) return Map.of();
        if (!(data instanceof Map<?, ?> map)) throw new IOException("Expected YAML mapping: " + file);
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(key.toString(), value));
        return result;
    }

    private static String readBounded(Path file, int maximum) throws IOException {
        StringBuilder text = new StringBuilder();
        int points = 0; boolean highSurrogate = false;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192]; int count;
            while ((count = reader.read(buffer)) != -1) {
                for (int i = 0; i < count; i++) {
                    char value = buffer[i];
                    if (!(highSurrogate && Character.isLowSurrogate(value))) points++;
                    highSurrogate = Character.isHighSurrogate(value);
                    if (points > maximum) throw new IOException("YAML document exceeds the supported read/write limit of " + maximum + " Unicode code points");
                }
                text.append(buffer, 0, count);
            }
        }
        return text.toString();
    }

    static void write(Path file, Map<String, Object> data) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        writeText(file, new Yaml(options).dump(data));
    }

    static void writeText(Path file, String data) throws IOException {
        writeText(file, data, MAX_CODE_POINTS);
    }
    static void writeText(Path file, String data, int maximum) throws IOException {
        validateSize(data, maximum);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), file.getFileName().toString(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(data);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    static void validateSize(String data, int maximum) throws IOException {
        if (data.codePointCount(0, data.length()) > maximum)
            throw new IOException("YAML document exceeds the supported read/write limit of " + maximum + " Unicode code points");
    }

    static long number(Map<String, Object> data, String key) {
        return data.get(key) instanceof Number value ? value.longValue() : 0L;
    }

    static Map<String, Object> freeze(Map<String, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(key, immutable(value)));
        return Collections.unmodifiableMap(result);
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key.toString(), immutable(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(YamlFiles::immutable).toList();
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw new IllegalArgumentException("Unsupported YAML value: " + value.getClass());
    }
}
