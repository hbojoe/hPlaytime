package com.hboj.hPlaytime;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShadedArtifactTest {
    @TempDir Path temporary;

    @Test void bundlesIsolatedLibrariesAndJava17CompatibleBaseClasses() throws Exception {
        Path release = Path.of(System.getProperty("hplaytime.releaseJar"));
        try (JarFile jar = new JarFile(release.toFile())) {
            assertNotNull(jar.getEntry("plugin.yml"));
            assertNotNull(jar.getEntry("com/hboj/hPlaytime/libs/hikari/HikariDataSource.class"));
            assertNotNull(jar.getEntry("com/hboj/hPlaytime/libs/yaml/Yaml.class"));
            assertNotNull(jar.getEntry("com/hboj/hPlaytime/libs/gson/Gson.class"));
            assertNotNull(jar.getEntry("META-INF/services/com.hboj.hPlaytime.libs.slf4j.spi.SLF4JServiceProvider"));
            for (var entry : jar.stream().toList()) {
                assertFalse(entry.getName().startsWith("org/yaml/snakeyaml/"));
                assertFalse(entry.getName().startsWith("com/zaxxer/hikari/"));
                assertFalse(entry.getName().startsWith("org/slf4j/"));
                if (entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/versions/")) {
                    try (var stream = jar.getInputStream(entry)) {
                        byte[] header = stream.readNBytes(8);
                        int major = ((header[6] & 255) << 8) | (header[7] & 255);
                        assertTrue(major <= 61, () -> "Java >17 class: " + entry.getName());
                    }
                }
            }
        }
        // No Paper, SLF4J, or YAML from the test/server classpath: exercise the relocated artifact itself.
        try (URLClassLoader loader = new URLClassLoader(new URL[]{release.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class.forName("com.hboj.hPlaytime.libs.hikari.HikariConfig", true, loader).getConstructor().newInstance();
            Class<?> yamlFiles = Class.forName("com.hboj.hPlaytime.YamlFiles", true, loader);
            var read = yamlFiles.getDeclaredMethod("read", Path.class);
            read.setAccessible(true);
            Path file = temporary.resolve("test.yml");
            Files.writeString(file, "daily:\n  2026-10-27: 123\n");
            Map<?, ?> decoded = (Map<?, ?>) read.invoke(null, file);
            assertEquals(123, ((Map<?, ?>) decoded.get("daily")).get("2026-10-27"));
        }
    }
}
