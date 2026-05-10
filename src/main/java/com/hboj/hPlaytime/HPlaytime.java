package com.hboj.hPlaytime;

import java.io.File;
import java.util.Objects;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

public final class HPlaytime extends JavaPlugin {
    private Lang lang;
    private PlaytimeManager playtimeManager;
    private EventManager eventManager;
    private BukkitTask flushTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveDefaultLang();

        try {
            lang = new Lang(this);
            eventManager = new EventManager(this, lang);
            PluginSettings settings = PluginSettings.load(this);
            playtimeManager = new PlaytimeManager(this, settings, eventManager);
            startFlushTask(settings);
        } catch (Exception exception) {
            getLogger().severe("Could not start hPlaytime: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new PlayerSessionListener(playtimeManager), this);

        PlaytimeCommand playtimeCommand = new PlaytimeCommand(this, playtimeManager, lang);
        Objects.requireNonNull(getCommand("playtime"), "playtime command").setExecutor(playtimeCommand);
        Objects.requireNonNull(getCommand("playtime"), "playtime command").setTabCompleter(playtimeCommand);
        Objects.requireNonNull(getCommand("hplaytime"), "hplaytime command").setExecutor(playtimeCommand);
        Objects.requireNonNull(getCommand("hplaytime"), "hplaytime command").setTabCompleter(playtimeCommand);
        Objects.requireNonNull(getCommand("event"), "event command").setExecutor(playtimeCommand);
        Objects.requireNonNull(getCommand("event"), "event command").setTabCompleter(playtimeCommand);

        registerPlaceholderApi();
    }

    @Override
    public void onDisable() {
        if (playtimeManager != null) {
            playtimeManager.stopTrackingAll();
            playtimeManager.close();
        }
        if (flushTask != null) {
            flushTask.cancel();
        }
    }

    public Lang lang() {
        return lang;
    }

    public PlaytimeManager playtimeManager() {
        return playtimeManager;
    }

    public EventManager eventManager() {
        return eventManager;
    }

    public void startFlushTask(PluginSettings settings) {
        if (flushTask != null) {
            flushTask.cancel();
        }
        flushTask = getServer().getScheduler().runTaskTimer(
            this,
            () -> {
                playtimeManager.flushOnlinePlayers();
                eventManager.tick();
            },
            settings.flushIntervalTicks(),
            settings.flushIntervalTicks()
        );
    }

    private void saveDefaultLang() {
        File langFile = new File(getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            saveResource("lang.yml", false);
        }
    }

    private void registerPlaceholderApi() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }

        new HPlaytimePlaceholderExpansion(this).register();
        getLogger().info("Registered PlaceholderAPI placeholders.");
    }
}
