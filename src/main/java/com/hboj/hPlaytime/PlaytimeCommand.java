package com.hboj.hPlaytime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class PlaytimeCommand implements CommandExecutor, TabCompleter {
    private final HPlaytime plugin;
    private final PlaytimeManager playtimeManager;
    private final EventManager eventManager;
    private final Lang lang;
    private static final int LEADERBOARD_LIMIT = 10;

    public PlaytimeCommand(HPlaytime plugin, PlaytimeManager playtimeManager, Lang lang) {
        this.plugin = plugin;
        this.playtimeManager = playtimeManager;
        this.eventManager = plugin.eventManager();
        this.lang = lang;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        if (commandName.equals("hplaytime")) {
            return handleAdminCommand(sender, args);
        }
        if (commandName.equals("event")) {
            return handleEventCommand(sender, args);
        }

        return handlePlaytimeCommand(sender, args);
    }

    private boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("hplaytime.reload")) {
                lang.send(sender, "no-permission");
                return true;
            }

            lang.reload();
            try {
                PluginSettings settings = PluginSettings.load(plugin);
                playtimeManager.reload(settings);
                plugin.startFlushTask(settings);
            } catch (Exception exception) {
                plugin.getLogger().severe("Could not reload hPlaytime storage: " + exception.getMessage());
                lang.send(sender, "reload-failed");
                return true;
            }
            lang.send(sender, "reloaded");
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            return resetPlayer(sender, args[1]);
        }

        if ((args.length == 1 && args[0].equalsIgnoreCase("resetall"))
            || (args.length == 2 && args[0].equalsIgnoreCase("reset") && args[1].equalsIgnoreCase("all"))) {
            return resetAll(sender);
        }

        lang.send(sender, "usage-admin");
        return true;
    }

    private boolean handlePlaytimeCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hplaytime.use")) {
            lang.send(sender, "no-permission");
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                lang.send(sender, "player-only");
                return true;
            }

            sendPlaytime(sender, player.getUniqueId(), player.getName());
            return true;
        }

        if (args[0].equalsIgnoreCase("event")) {
            return handleEventCommand(sender, copyArgs(args, 1));
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("top")) {
            sendLeaderboard(sender, "All Time", playtimeManager.getTopAllTime(LEADERBOARD_LIMIT));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("topday")) {
            sendLeaderboard(sender, "Today", playtimeManager.getTopDay(LEADERBOARD_LIMIT));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("topmonth")) {
            sendLeaderboard(sender, "This Month", playtimeManager.getTopMonth(LEADERBOARD_LIMIT));
            return true;
        }

        if (args[0].equalsIgnoreCase("player")) {
            if (args.length == 2) {
                return sendTargetPlaytime(sender, args[1]);
            }

            lang.send(sender, "usage-playtime");
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            return startEvent(sender, args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            return resetPlayer(sender, args[1]);
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("resetall")) {
            return resetAll(sender);
        }

        if (args.length != 1) {
            lang.send(sender, "usage-playtime");
            return true;
        }

        return sendTargetPlaytime(sender, args[0]);
    }

    private boolean handleEventCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            lang.send(sender, "usage-event");
            return true;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            if (!sender.hasPermission("hplaytime.event.create")) {
                lang.send(sender, "no-permission");
                return true;
            }

            EventManager.CreateResult result = eventManager.create(args[1], args[2]);
            switch (result) {
                case CREATED -> lang.send(sender, "event-created", Map.of("event", args[1], "duration", args[2]));
                case INVALID_NAME -> lang.send(sender, "event-invalid-name");
                case INVALID_DURATION -> lang.send(sender, "event-invalid-duration");
                case ALREADY_EXISTS -> lang.send(sender, "event-already-exists", Map.of("event", args[1]));
            }
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            return startEvent(sender, args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("end")) {
            if (!sender.hasPermission("hplaytime.event.end")) {
                lang.send(sender, "no-permission");
                return true;
            }

            EventManager.ActionResult result = eventManager.end(args[1]);
            sendEventActionResult(sender, result, "event-ended-manual", args[1]);
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
            if (!sender.hasPermission("hplaytime.event.delete")) {
                lang.send(sender, "no-permission");
                return true;
            }

            EventManager.ActionResult result = eventManager.delete(args[1]);
            sendEventActionResult(sender, result, "event-deleted", args[1]);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            if (!sender.hasPermission("hplaytime.event.list")) {
                lang.send(sender, "no-permission");
                return true;
            }

            String events = eventManager.eventNames().isEmpty() ? "none" : String.join(", ", eventManager.eventNames());
            lang.send(sender, "event-list", Map.of("events", events));
            return true;
        }

        lang.send(sender, "usage-event");
        return true;
    }

    private boolean startEvent(CommandSender sender, String eventName) {
        if (!sender.hasPermission("hplaytime.event.start")) {
            lang.send(sender, "no-permission");
            return true;
        }

        EventManager.ActionResult result = eventManager.start(eventName);
        sendEventActionResult(sender, result, "event-started", eventName);
        return true;
    }

    private void sendEventActionResult(CommandSender sender, EventManager.ActionResult result, String successKey, String eventName) {
        switch (result) {
            case OK -> lang.send(sender, successKey, Map.of("event", eventName));
            case NOT_FOUND -> lang.send(sender, "event-not-found", Map.of("event", eventName));
            case ALREADY_ACTIVE -> lang.send(sender, "event-already-active", Map.of("event", eventName));
            case NOT_ACTIVE -> lang.send(sender, "event-not-active", Map.of("event", eventName));
        }
    }

    private boolean resetPlayer(CommandSender sender, String playerName) {
        if (!sender.hasPermission("hplaytime.reset")) {
            lang.send(sender, "no-permission");
            return true;
        }

        StoredPlayer target = findPlayer(playerName);
        if (target == null) {
            lang.send(sender, "unknown-player", Map.of("player", playerName));
            return true;
        }

        if (playtimeManager.resetPlayer(target.uuid(), target.name())) {
            lang.send(sender, "reset-player", Map.of("player", target.name()));
        } else {
            lang.send(sender, "reset-failed", Map.of("player", target.name()));
        }
        return true;
    }

    private boolean resetAll(CommandSender sender) {
        if (!sender.hasPermission("hplaytime.resetall")) {
            lang.send(sender, "no-permission");
            return true;
        }

        if (playtimeManager.resetAllPlayers()) {
            lang.send(sender, "reset-all");
        } else {
            lang.send(sender, "reset-all-failed");
        }
        return true;
    }

    private StoredPlayer findPlayer(String name) {
        Player onlinePlayer = plugin.getServer().getPlayerExact(name);
        if (onlinePlayer != null) {
            return new StoredPlayer(onlinePlayer.getUniqueId(), onlinePlayer.getName());
        }

        OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(name);
        if (offlinePlayer.hasPlayedBefore()) {
            return new StoredPlayer(offlinePlayer.getUniqueId(), offlinePlayer.getName());
        }

        return playtimeManager.findStoredPlayer(name).orElse(null);
    }

    private boolean sendTargetPlaytime(CommandSender sender, String playerName) {
        StoredPlayer target = findPlayer(playerName);
        if (target == null) {
            lang.send(sender, "unknown-player", Map.of("player", playerName));
            return true;
        }

        if (sender instanceof Player player && target.uuid().equals(player.getUniqueId())) {
            sendPlaytime(sender, target.uuid(), target.name());
            return true;
        }

        if (!sender.hasPermission("hplaytime.others")) {
            lang.send(sender, "no-permission");
            return true;
        }

        sendPlaytime(sender, target.uuid(), target.name());
        return true;
    }

    private void sendPlaytime(CommandSender sender, UUID targetUuid, String fallbackName) {
        PlaytimeSnapshot snapshot = playtimeManager.getSnapshot(targetUuid, fallbackName);
        String playerName = snapshot.playerName() == null ? targetUuid.toString() : snapshot.playerName();

        lang.sendList(sender, "playtime", Map.of(
            "player", playerName,
            "today", TimeFormatter.format(snapshot.todayMillis(), playtimeManager.timeFormatterSettings()),
            "month", TimeFormatter.format(snapshot.monthMillis(), playtimeManager.timeFormatterSettings()),
            "alltime", TimeFormatter.format(snapshot.alltimeMillis(), playtimeManager.timeFormatterSettings())
        ));

        if (playtimeManager.lastSeenEnabled()) {
            lang.send(sender, "playtime-last-seen", Map.of(
                "player", playerName,
                "lastseen", lastSeenText(targetUuid, snapshot)
            ));
        }
    }

    private void sendLeaderboard(CommandSender sender, String period, List<PlaytimeLeaderboardEntry> entries) {
        lang.send(sender, "leaderboard-header", Map.of("period", period));
        if (entries.isEmpty()) {
            lang.send(sender, "leaderboard-empty", Map.of("period", period));
            return;
        }

        for (int index = 0; index < entries.size(); index++) {
            PlaytimeLeaderboardEntry entry = entries.get(index);
            lang.send(sender, "leaderboard-entry", Map.of(
                "rank", Integer.toString(index + 1),
                "player", entry.playerName(),
                "time", TimeFormatter.format(entry.millis(), playtimeManager.timeFormatterSettings()),
                "period", period
            ));
        }
    }

    private String lastSeenText(UUID targetUuid, PlaytimeSnapshot snapshot) {
        if (plugin.getServer().getPlayer(targetUuid) != null) {
            return "online now";
        }
        if (snapshot.lastSeenMillis() <= 0L) {
            return "unknown";
        }
        return playtimeManager.formatLastSeen(snapshot.lastSeenMillis());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        if (commandName.equals("hplaytime")) {
            if (args.length == 1) {
                List<String> values = new ArrayList<>();
                if (sender.hasPermission("hplaytime.reload")) {
                    values.add("reload");
                }
                if (sender.hasPermission("hplaytime.reset")) {
                    values.add("reset");
                }
                if (sender.hasPermission("hplaytime.resetall")) {
                    values.add("resetall");
                }
                return startsWith(values, args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
                return resetPlayerNameCompletions(sender, args[1]);
            }
            return Collections.emptyList();
        }

        if (commandName.equals("event")) {
            return eventTabComplete(sender, args);
        }

        if (args.length == 1) {
            Set<String> values = new LinkedHashSet<>();
            values.add("top");
            values.add("topday");
            values.add("topmonth");
            values.add("player");
            if (sender.hasPermission("hplaytime.others")) {
                values.addAll(knownPlayerNames(true));
            } else if (sender instanceof Player player) {
                values.add(player.getName());
            }
            if (hasAnyEventPermission(sender)) {
                values.add("event");
            }
            if (sender.hasPermission("hplaytime.event.start")) {
                values.add("start");
            }
            if (sender.hasPermission("hplaytime.reset")) {
                values.add("reset");
            }
            if (sender.hasPermission("hplaytime.resetall")) {
                values.add("resetall");
            }
            return startsWith(new ArrayList<>(values), args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("player")) {
            return playerNameCompletions(sender, args[1], false);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            if (!sender.hasPermission("hplaytime.event.start")) {
                return Collections.emptyList();
            }
            return startsWith(eventManager.eventNames(), args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            return resetPlayerNameCompletions(sender, args[1]);
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("event")) {
            return eventTabComplete(sender, copyArgs(args, 1));
        }

        return Collections.emptyList();
    }

    private List<String> eventTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>();
            if (sender.hasPermission("hplaytime.event.create")) {
                values.add("create");
            }
            if (sender.hasPermission("hplaytime.event.start")) {
                values.add("start");
            }
            if (sender.hasPermission("hplaytime.event.end")) {
                values.add("end");
            }
            if (sender.hasPermission("hplaytime.event.delete")) {
                values.add("delete");
            }
            if (sender.hasPermission("hplaytime.event.list")) {
                values.add("list");
            }
            return startsWith(values, args[0]);
        }

        if (args.length == 2
            && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("end") || args[0].equalsIgnoreCase("delete"))) {
            if (!canCompleteEventNames(sender, args[0])) {
                return Collections.emptyList();
            }
            return startsWith(eventManager.eventNames(), args[1]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            if (!sender.hasPermission("hplaytime.event.create")) {
                return Collections.emptyList();
            }
            return startsWith(List.of("24h", "12h", "7d", "30m"), args[2]);
        }

        return Collections.emptyList();
    }

    private boolean hasAnyEventPermission(CommandSender sender) {
        return sender.hasPermission("hplaytime.event.create")
            || sender.hasPermission("hplaytime.event.start")
            || sender.hasPermission("hplaytime.event.end")
            || sender.hasPermission("hplaytime.event.delete")
            || sender.hasPermission("hplaytime.event.list");
    }

    private boolean canCompleteEventNames(CommandSender sender, String action) {
        if (action.equalsIgnoreCase("start")) {
            return sender.hasPermission("hplaytime.event.start");
        }
        if (action.equalsIgnoreCase("end")) {
            return sender.hasPermission("hplaytime.event.end");
        }
        if (action.equalsIgnoreCase("delete")) {
            return sender.hasPermission("hplaytime.event.delete");
        }
        return false;
    }

    private List<String> playerNameCompletions(CommandSender sender, String prefix, boolean includeOffline) {
        if (!sender.hasPermission("hplaytime.others")) {
            if (sender instanceof Player player) {
                return startsWith(List.of(player.getName()), prefix);
            }
            return Collections.emptyList();
        }

        return startsWith(knownPlayerNames(includeOffline), prefix);
    }

    private List<String> resetPlayerNameCompletions(CommandSender sender, String prefix) {
        if (!sender.hasPermission("hplaytime.reset")) {
            return Collections.emptyList();
        }

        return startsWith(knownPlayerNames(true), prefix);
    }

    private List<String> knownPlayerNames(boolean includeOffline) {
        Set<String> names = new LinkedHashSet<>();
        plugin.getServer().getOnlinePlayers().stream()
            .map(Player::getName)
            .forEach(names::add);

        if (includeOffline) {
            for (OfflinePlayer offlinePlayer : plugin.getServer().getOfflinePlayers()) {
                String name = offlinePlayer.getName();
                if (name != null && offlinePlayer.hasPlayedBefore()) {
                    names.add(name);
                }
            }
        }

        return new ArrayList<>(names);
    }

    private List<String> startsWith(List<String> values, String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                matches.add(value);
            }
        }
        return matches;
    }

    private String[] copyArgs(String[] args, int startIndex) {
        String[] copy = new String[args.length - startIndex];
        System.arraycopy(args, startIndex, copy, 0, copy.length);
        return copy;
    }
}
