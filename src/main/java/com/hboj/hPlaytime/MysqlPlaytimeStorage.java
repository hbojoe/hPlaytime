package com.hboj.hPlaytime;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

public final class MysqlPlaytimeStorage implements PlaytimeStorage {
    private static final String ALLTIME_PERIOD = "all";

    private final PluginSettings.MysqlSettings settings;
    private final String playersTable;
    private final String playtimeTable;

    public MysqlPlaytimeStorage(PluginSettings.MysqlSettings settings) {
        this.settings = settings;
        this.playersTable = sanitizePrefix(settings.tablePrefix()) + "players";
        this.playtimeTable = sanitizePrefix(settings.tablePrefix()) + "playtime";
    }

    @Override
    public void initialize() throws SQLException, ClassNotFoundException {
        Class.forName("com.mysql.cj.jdbc.Driver");

        try (Connection connection = connection();
             PreparedStatement playersStatement = connection.prepareStatement("""
                 CREATE TABLE IF NOT EXISTS %s (
                   uuid CHAR(36) NOT NULL PRIMARY KEY,
                   name VARCHAR(16) NOT NULL,
                   last_seen_millis BIGINT NULL,
                   updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                   INDEX name_index (name)
                 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                 """.formatted(playersTable));
             PreparedStatement playtimeStatement = connection.prepareStatement("""
                 CREATE TABLE IF NOT EXISTS %s (
                   uuid CHAR(36) NOT NULL,
                   period_type VARCHAR(16) NOT NULL,
                   period_key VARCHAR(32) NOT NULL,
                   millis BIGINT NOT NULL DEFAULT 0,
                   PRIMARY KEY (uuid, period_type, period_key)
                 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                 """.formatted(playtimeTable))) {
            playersStatement.executeUpdate();
            playtimeStatement.executeUpdate();
        }

        try (Connection connection = connection()) {
            ensureLastSeenColumn(connection);
        }
    }

    @Override
    public void updateName(UUID uuid, String playerName) throws SQLException {
        try (Connection connection = connection()) {
            updateName(connection, uuid, playerName);
        }
    }

    @Override
    public void updateLastSeen(UUID uuid, String playerName, long lastSeenMillis) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO " + playersTable + " (uuid, name, last_seen_millis) VALUES (?, ?, ?) "
                     + "ON DUPLICATE KEY UPDATE name = VALUES(name), last_seen_millis = VALUES(last_seen_millis)"
             )) {
            statement.setString(1, uuid.toString());
            statement.setString(2, playerName);
            statement.setLong(3, lastSeenMillis);
            statement.executeUpdate();
        }
    }

    @Override
    public void addPlaytime(UUID uuid, String playerName, List<PlaytimeIncrement> increments) throws SQLException {
        if (increments.isEmpty()) {
            return;
        }

        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                updateName(connection, uuid, playerName);

                long totalMillis = 0L;
                for (PlaytimeIncrement increment : increments) {
                    totalMillis += increment.millis();
                    incrementPeriod(connection, uuid, "daily", increment.dayKey(), increment.millis());
                    incrementPeriod(connection, uuid, "monthly", increment.monthKey(), increment.millis());
                }
                incrementPeriod(connection, uuid, "alltime", ALLTIME_PERIOD, totalMillis);

                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    @Override
    public PlaytimeSnapshot getSnapshot(UUID uuid, String fallbackName, String todayKey, String monthKey) throws SQLException {
        try (Connection connection = connection()) {
            String name = fallbackName;
            long lastSeenMillis = 0L;
            try (PreparedStatement statement = connection.prepareStatement("SELECT name, last_seen_millis FROM " + playersTable + " WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        name = resultSet.getString("name");
                        lastSeenMillis = resultSet.getLong("last_seen_millis");
                    }
                }
            }

            return new PlaytimeSnapshot(
                uuid,
                name,
                getPeriodMillis(connection, uuid, "daily", todayKey),
                getPeriodMillis(connection, uuid, "monthly", monthKey),
                getPeriodMillis(connection, uuid, "alltime", ALLTIME_PERIOD),
                lastSeenMillis
            );
        }
    }

    @Override
    public List<PlaytimeLeaderboardEntry> getTop(String periodType, String periodKey, int limit) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT pt.uuid, p.name, pt.millis FROM " + playtimeTable + " pt "
                     + "LEFT JOIN " + playersTable + " p ON p.uuid = pt.uuid "
                     + "WHERE pt.period_type = ? AND pt.period_key = ? AND pt.millis > 0 "
                     + "ORDER BY pt.millis DESC LIMIT ?"
             )) {
            statement.setString(1, periodType);
            statement.setString(2, periodKey);
            statement.setInt(3, limit);

            List<PlaytimeLeaderboardEntry> entries = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String uuid = resultSet.getString("uuid");
                    String name = resultSet.getString("name");
                    entries.add(new PlaytimeLeaderboardEntry(
                        UUID.fromString(uuid),
                        name == null ? uuid : name,
                        resultSet.getLong("millis")
                    ));
                }
            }
            return entries;
        }
    }

    @Override
    public List<String> getKnownPlayerNames() throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("SELECT name FROM " + playersTable + " ORDER BY name ASC");
             ResultSet resultSet = statement.executeQuery()) {
            List<String> names = new ArrayList<>();
            while (resultSet.next()) {
                String name = resultSet.getString("name");
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
            return names;
        }
    }

    @Override
    public Optional<StoredPlayer> findByName(String playerName) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT uuid, name FROM " + playersTable + " WHERE LOWER(name) = LOWER(?) LIMIT 1"
             )) {
            statement.setString(1, playerName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(new StoredPlayer(
                        UUID.fromString(resultSet.getString("uuid")),
                        resultSet.getString("name")
                    ));
                }
            }
        }

        return Optional.empty();
    }

    @Override
    public void resetPlayer(UUID uuid) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM " + playtimeTable + " WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        }
    }

    @Override
    public void resetAllPlayers() throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM " + playtimeTable)) {
            statement.executeUpdate();
        }
    }

    @Override
    public void close() {
    }

    private void updateName(Connection connection, UUID uuid, String playerName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + playersTable + " (uuid, name) VALUES (?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name)"
        )) {
            statement.setString(1, uuid.toString());
            statement.setString(2, playerName);
            statement.executeUpdate();
        }
    }

    private void ensureLastSeenColumn(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SHOW COLUMNS FROM " + playersTable + " LIKE 'last_seen_millis'");
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                return;
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(
            "ALTER TABLE " + playersTable + " ADD COLUMN last_seen_millis BIGINT NULL AFTER name"
        )) {
            statement.executeUpdate();
        }
    }

    private void incrementPeriod(Connection connection, UUID uuid, String periodType, String periodKey, long millis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + playtimeTable + " (uuid, period_type, period_key, millis) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE millis = millis + VALUES(millis)"
        )) {
            statement.setString(1, uuid.toString());
            statement.setString(2, periodType);
            statement.setString(3, periodKey);
            statement.setLong(4, millis);
            statement.executeUpdate();
        }
    }

    private long getPeriodMillis(Connection connection, UUID uuid, String periodType, String periodKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT millis FROM " + playtimeTable + " WHERE uuid = ? AND period_type = ? AND period_key = ?"
        )) {
            statement.setString(1, uuid.toString());
            statement.setString(2, periodType);
            statement.setString(3, periodKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong("millis");
                }
            }
        }

        return 0L;
    }

    private Connection connection() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", settings.username());
        properties.setProperty("password", settings.password());
        properties.setProperty("useSSL", Boolean.toString(settings.useSsl()));
        properties.setProperty("allowPublicKeyRetrieval", "true");
        properties.setProperty("serverTimezone", "UTC");

        return DriverManager.getConnection(
            "jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database(),
            properties
        );
    }

    private static String sanitizePrefix(String prefix) {
        String safePrefix = prefix == null ? "hplaytime_" : prefix;
        if (!safePrefix.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("MySQL table-prefix may only contain letters, numbers, and underscores.");
        }
        return safePrefix;
    }
}
