# hPlaytime

A standalone Paper plugin for tracking Minecraft playtime without relying on Essentials or any other playtime plugin.

hPlaytime tracks:

- Today's playtime
- This month's playtime
- All-time playtime
- Temporary playtime events

It includes its own data files, language file, config, AFK safety, optional MySQL storage, and event logs.

## Features

- Independent playtime tracking
- Local YAML or MySQL storage
- Daily, monthly, and all-time totals
- Optional last-seen display in `/playtime`
- Configurable date and time formatting
- Built-in AFK safety
- AFK worlds where no playtime is counted
- Text-only commands, no menus
- Reset one player's playtime
- Reset all main playtime
- Create timed playtime events
- Event winner broadcasts
- Permanent event result logs
- Fully editable `lang.yml`

## Requirements

- Paper server
- Java 21

MySQL is optional. hPlaytime uses local YAML storage by default.

## Installation

1. Download the latest hPlaytime jar from the releases page.
2. Put the jar in your server's `plugins` folder.
3. Restart the server.
4. Edit `plugins/hPlaytime/config.yml` and `plugins/hPlaytime/lang.yml` as needed.
5. Run `/hplaytime reload` after editing config or language files.

## Plugin Files

hPlaytime creates this folder:

```text
plugins/hPlaytime/
```

Important files and folders:

| Path | Purpose |
| --- | --- |
| `config.yml` | Storage, formatting, AFK, AFK worlds, and flush settings |
| `lang.yml` | All editable plugin messages |
| `data/` | Local player playtime files when using local storage |
| `events/` | Active/created event tracking files |
| `event-logs/` | Saved event result logs |

## Commands

### Playtime

| Command | Description |
| --- | --- |
| `/playtime` | Shows your own playtime |
| `/pt` | Alias for `/playtime` |
| `/playtime <player>` | Shows another player's playtime |

### Admin

| Command | Description |
| --- | --- |
| `/hplaytime reload` | Reloads config, language, storage settings, AFK settings, and formats |
| `/playtime reset <player>` | Resets one player's main playtime |
| `/hplaytime reset <player>` | Same as above |
| `/playtime resetall` | Resets all main playtime |
| `/hplaytime resetall` | Same as above |

### Events

| Command | Description |
| --- | --- |
| `/playtime event create <name> <duration>` | Creates a playtime event |
| `/playtime start <name>` | Starts an event fresh |
| `/playtime event end <name>` | Ends an event and writes a log |
| `/event end <name>` | Same as above |
| `/playtime event delete <name>` | Deletes active event tracking files |
| `/event delete <name>` | Same as above |
| `/event list` | Lists events |

Example:

```text
/playtime event create playtimediscord 24h
/playtime start playtimediscord
```

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `hplaytime.use` | Everyone | Use `/playtime` |
| `hplaytime.others` | OP | Check another player's playtime |
| `hplaytime.reload` | OP | Reload hPlaytime |
| `hplaytime.reset` | OP | Reset one player's main playtime |
| `hplaytime.resetall` | OP | Reset all main playtime |
| `hplaytime.event.create` | OP | Create playtime events |
| `hplaytime.event.start` | OP | Start playtime events |
| `hplaytime.event.end` | OP | End playtime events |
| `hplaytime.event.delete` | OP | Delete event tracking files |
| `hplaytime.event.list` | OP | List events |

## Storage

hPlaytime can store main playtime locally or in MySQL.

### Local Storage

Default config:

```yaml
storage:
  type: local
```

Local storage writes one file per player:

```text
plugins/hPlaytime/data/<uuid>.yml
```

This is the simplest option and is recommended for single-server setups.

### MySQL Storage

To use MySQL:

```yaml
storage:
  type: mysql

  mysql:
    host: localhost
    port: 3306
    database: minecraft
    username: root
    password: ""
    use-ssl: false
    table-prefix: hplaytime_
```

hPlaytime creates its MySQL tables automatically:

- `hplaytime_players`
- `hplaytime_playtime`

The MySQL driver is included in the plugin jar.

## Date And Time Formatting

Date keys and timezone are configured in `config.yml`:

```yaml
date-format:
  timezone: system
  daily-pattern: yyyy-MM-dd
  monthly-pattern: yyyy-MM
```

Use `timezone: system` for the server machine timezone, or use a Java timezone ID:

```yaml
timezone: America/Chicago
```

Time output is also configurable:

```yaml
time-format:
  style: compact
  show-seconds: true
  zero: 0s
```

Compact output example:

```text
1d 4h 22m
```

Long output example:

```text
1 day 4 hours 22 minutes
```

Last-seen output can be disabled or reformatted:

```yaml
last-seen:
  enabled: true
  pattern: yyyy-MM-dd HH:mm:ss z
```

## AFK Safety

hPlaytime has its own AFK safety. It does not use Essentials AFK state.

```yaml
afk:
  enabled: true
  timeout-seconds: 300

  worlds:
    - afk
```

When a player is inactive for the configured timeout, new playtime stops counting until they move, interact, or run a command.

Players in worlds listed under `afk.worlds` do not gain:

- Main playtime
- Event playtime

## Events

Events are separate from main playtime.

When you start an event, that event starts fresh even if it was created earlier.

Supported duration units:

| Unit | Meaning |
| --- | --- |
| `d` | Days |
| `h` | Hours |
| `m` | Minutes |
| `s` | Seconds |

Valid examples:

```text
24h
7d
1h30m
45m
```

When an event ends, hPlaytime broadcasts the highest event playtime in chat.

Event tracking files:

```text
plugins/hPlaytime/events/
```

Event result logs:

```text
plugins/hPlaytime/event-logs/
```

Deleting an event removes the active tracking file but keeps existing logs.

Event tracking is currently local-file based, even when main playtime storage is set to MySQL.

## Language File

All messages are editable in:

```text
plugins/hPlaytime/lang.yml
```

Messages support `&` color codes.

Common placeholders:

| Placeholder | Meaning |
| --- | --- |
| `%player%` | Player name |
| `%today%` | Today's playtime |
| `%month%` | This month's playtime |
| `%alltime%` | All-time playtime |
| `%lastseen%` | Last-seen time, `online now`, or `unknown` |
| `%event%` | Event name |
| `%duration%` | Event duration |
| `%time%` | Formatted time |
| `%events%` | Event list |

After editing `lang.yml`, run:

```text
/hplaytime reload
```

## Important Notes

- Reset commands affect main playtime only.
- Reset commands do not delete event logs.
- AFK timeout applies to main playtime and event playtime.
- AFK worlds apply to main playtime and event playtime.
- Event logs are intentionally kept after an event is deleted.
- hPlaytime does not import Essentials playtime.
- hPlaytime does not use Minecraft statistics for all-time totals; it tracks its own data from the time it is installed.
