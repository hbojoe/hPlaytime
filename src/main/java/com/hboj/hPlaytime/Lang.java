package com.hboj.hPlaytime;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public final class Lang {
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();
    private static final Map<String, String> DEFAULT_MESSAGES = Map.of(
        "playtime-last-seen", "&7Last seen: &f%lastseen%",
        "leaderboard-header", "%prefix%&aTop 10 Playtime - &f%period%",
        "leaderboard-entry", "&7#%rank% &f%player% &8- &a%time%",
        "leaderboard-empty", "%prefix%&7No playtime has been tracked for &f%period%&7 yet."
    );

    private final HPlaytime plugin;
    private FileConfiguration messages;

    public Lang(HPlaytime plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "lang.yml");
        messages = YamlConfiguration.loadConfiguration(file);
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Collections.emptyMap());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(LEGACY_SERIALIZER.deserialize(format(getString(key), placeholders)));
    }

    public void sendList(CommandSender sender, String key, Map<String, String> placeholders) {
        for (String line : messages.getStringList(key)) {
            sender.sendMessage(LEGACY_SERIALIZER.deserialize(format(line, placeholders)));
        }
    }

    public void broadcast(String key, Map<String, String> placeholders) {
        for (String line : messages.getStringList(key)) {
            plugin.getServer().sendMessage(LEGACY_SERIALIZER.deserialize(format(line, placeholders)));
        }
    }

    private String getString(String key) {
        return messages.getString(key, DEFAULT_MESSAGES.getOrDefault(key, key));
    }

    private String format(String message, Map<String, String> placeholders) {
        String formatted = message.replace("%prefix%", messages.getString("prefix", ""));
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            formatted = formatted.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return formatted;
    }
}
