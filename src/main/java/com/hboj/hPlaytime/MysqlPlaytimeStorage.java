package com.hboj.hPlaytime;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Worker-confined. The legacy tables remain additive; receipts and resets are transactional. */
public final class MysqlPlaytimeStorage implements PlaytimeStorage {
    private final PluginSettings.MysqlSettings settings;
    private final String playersTable, playtimeTable, stateTable, clocksTable, operationsTable;
    private HikariDataSource pool;

    public MysqlPlaytimeStorage(PluginSettings.MysqlSettings settings) { this(settings, null); }
    MysqlPlaytimeStorage(PluginSettings.MysqlSettings settings, HikariDataSource pool) {
        this.settings = settings; this.pool = pool;
        String prefix = settings.tablePrefix();
        if (prefix == null || !prefix.matches("[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("MySQL table-prefix may only contain letters, numbers, and underscores.");
        if (prefix.length() > 56) throw new IllegalArgumentException("MySQL table-prefix exceeds the legacy 56-character limit");
        playersTable = prefix + "players"; playtimeTable = prefix + "playtime";
        List<String> protocol = protocolTables(prefix);
        stateTable = protocol.get(0); clocksTable = protocol.get(1); operationsTable = protocol.get(2);
    }

    static List<String> protocolTables(String prefix) {
        return List.of(auxiliary(prefix, "persistence_state", "pt_state"),
            auxiliary(prefix, "persistence_players", "pt_clock"), auxiliary(prefix, "persistence_operations", "pt_ops"));
    }
    private static String auxiliary(String prefix, String suffix, String compact) {
        return prefix + (prefix.length() + suffix.length() <= 64 ? suffix : compact);
    }

    @Override public void initialize() throws Exception {
        pool = new HikariDataSource(poolConfig(settings));
        try (Connection connection = pool.getConnection()) {
            execute(connection, """
                CREATE TABLE IF NOT EXISTS %s (
                  uuid CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(16) NOT NULL,
                  last_seen_millis BIGINT NULL, hidden_from_leaderboards BOOLEAN NOT NULL DEFAULT FALSE,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  INDEX name_index (name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(playersTable));
            execute(connection, """
                CREATE TABLE IF NOT EXISTS %s (
                  uuid CHAR(36) NOT NULL, period_type VARCHAR(16) NOT NULL,
                  period_key VARCHAR(32) NOT NULL, millis BIGINT NOT NULL DEFAULT 0,
                  PRIMARY KEY (uuid, period_type, period_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(playtimeTable));
            ensureColumn(connection, "last_seen_millis", "BIGINT NULL AFTER name");
            ensureColumn(connection, "hidden_from_leaderboards", "BOOLEAN NOT NULL DEFAULT FALSE AFTER last_seen_millis");
            initializeProtocol(connection);
            verifyTransactionalTables(connection);
        } catch (Exception failure) { close(); throw failure; }
    }

    private void verifyTransactionalTables(Connection connection) throws SQLException {
        try (PreparedStatement statement = statement(connection,
                "SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN (?,?,?,?,?)")) {
            String[] tables = {playersTable, playtimeTable, stateTable, clocksTable, operationsTable};
            for (int i = 0; i < tables.length; i++) statement.setString(i + 1, tables[i]);
            int found = 0;
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found++;
                    if (!"InnoDB".equalsIgnoreCase(rows.getString("ENGINE")))
                        throw new SQLException("Exactly-once persistence requires InnoDB: " + rows.getString("TABLE_NAME"));
                }
            }
            if (found != tables.length) throw new SQLException("Could not verify all persistence table engines");
        }
    }

    void initializeProtocol(Connection connection) throws SQLException {
        execute(connection, "CREATE TABLE IF NOT EXISTS " + stateTable
            + " (id INTEGER NOT NULL PRIMARY KEY, reset_millis BIGINT NOT NULL) ENGINE=InnoDB");
        execute(connection, "CREATE TABLE IF NOT EXISTS " + clocksTable
            + " (uuid CHAR(36) NOT NULL PRIMARY KEY, reset_millis BIGINT NOT NULL, metadata_millis BIGINT NOT NULL) ENGINE=InnoDB");
        execute(connection, "CREATE TABLE IF NOT EXISTS " + operationsTable
            + " (operation_id CHAR(36) NOT NULL PRIMARY KEY, payload_hash CHAR(64) NOT NULL, "
            + "applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
        execute(connection, "INSERT INTO " + stateTable + " (id, reset_millis) VALUES (1,0) ON DUPLICATE KEY UPDATE id=id");
    }

    static HikariConfig poolConfig(PluginSettings.MysqlSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("hPlaytime"); config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl("jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database());
        config.setUsername(settings.username()); config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.maximumPoolSize()); config.setMinimumIdle(0);
        config.setConnectionTimeout(settings.connectionTimeoutMillis());
        config.setValidationTimeout(settings.validationTimeoutMillis()); config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("connectTimeout", settings.connectTimeoutMillis());
        config.addDataSourceProperty("socketTimeout", settings.socketTimeoutMillis());
        config.addDataSourceProperty("useSSL", settings.useSsl());
        config.addDataSourceProperty("allowPublicKeyRetrieval", true);
        config.addDataSourceProperty("serverTimezone", "UTC");
        config.addDataSourceProperty("rewriteBatchedStatements", true);
        return config;
    }

    private PreparedStatement statement(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(settings.queryTimeoutSeconds());
        return statement;
    }
    private void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = statement(connection, sql)) { statement.executeUpdate(); }
    }
    private void ensureColumn(Connection connection, String column, String definition) throws SQLException {
        try (PreparedStatement statement = statement(connection, "SHOW COLUMNS FROM " + playersTable + " LIKE '" + column + "'");
             ResultSet rows = statement.executeQuery()) { if (rows.next()) return; }
        execute(connection, "ALTER TABLE " + playersTable + " ADD COLUMN " + column + " " + definition);
    }

    @Override public StorageView loadView() throws Exception {
        try (Connection connection = pool.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                StorageView view = readView(connection);
                connection.commit();
                return view;
            } catch (Exception failure) { rollback(connection, failure); throw failure; }
        }
    }

    private StorageView readView(Connection connection) throws SQLException {
        long reset = globalReset(connection, false);
        Map<UUID, PlayerData> players = new HashMap<>();
        Map<UUID, StorageView.PlayerClock> clocks = new HashMap<>();
        Map<UUID, Map<String, Long>> daily = new HashMap<>(), monthly = new HashMap<>();
        Map<UUID, Long> totals = new HashMap<>();
        try (PreparedStatement statement = statement(connection,
                "SELECT uuid,name,last_seen_millis,hidden_from_leaderboards FROM " + playersTable);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID uuid = UUID.fromString(rows.getString("uuid"));
                String name = rows.getString("name");
                if (name == null) name = uuid.toString();
                players.put(uuid, PlayerData.empty(uuid, name).metadata(name, rows.getBoolean("hidden_from_leaderboards"), rows.getLong("last_seen_millis")));
            }
        }
        try (PreparedStatement statement = statement(connection, "SELECT uuid,period_type,period_key,millis FROM " + playtimeTable);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID uuid = UUID.fromString(rows.getString("uuid"));
                players.putIfAbsent(uuid, PlayerData.empty(uuid, uuid.toString()));
                String type = rows.getString("period_type"), key = rows.getString("period_key");
                long millis = rows.getLong("millis");
                if (type.equals("daily")) daily.computeIfAbsent(uuid, ignored -> new HashMap<>()).put(key, millis);
                else if (type.equals("monthly")) monthly.computeIfAbsent(uuid, ignored -> new HashMap<>()).put(key, millis);
                else if (type.equals("alltime") && key.equals("all")) totals.put(uuid, millis);
            }
        }
        try (PreparedStatement statement = statement(connection, "SELECT uuid,reset_millis,metadata_millis FROM " + clocksTable);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) clocks.put(UUID.fromString(rows.getString("uuid")),
                new StorageView.PlayerClock(rows.getLong("reset_millis"), rows.getLong("metadata_millis")));
        }
        players.replaceAll((uuid, player) -> new PlayerData(uuid, player.name(), player.lastSeen(), player.hidden(),
            totals.getOrDefault(uuid, 0L), daily.getOrDefault(uuid, Map.of()), monthly.getOrDefault(uuid, Map.of()), Map.of()));
        return new StorageView(players, reset, clocks);
    }

    @Override public void apply(StorageMutation mutation) throws Exception {
        String hash = mutation.hash();
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Serializes resets and contributions from all 1.0.7 writers for this prefix.
                long globalCutoff = globalReset(connection, true);
                if (receipt(connection, mutation.id(), hash)) { connection.rollback(); return; }
                switch (mutation.kind()) {
                    case ADD -> add(connection, mutation, globalCutoff);
                    case RESET_PLAYER -> resetPlayer(connection, mutation.target(), databaseMillis(connection));
                    case RESET_ALL -> {
                        execute(connection, "DELETE FROM " + playtimeTable);
                        try (PreparedStatement statement = statement(connection, "UPDATE " + stateTable + " SET reset_millis=? WHERE id=1")) {
                            statement.setLong(1, Math.max(globalCutoff, databaseMillis(connection))); statement.executeUpdate();
                        }
                    }
                }
                try (PreparedStatement statement = statement(connection,
                        "INSERT INTO " + operationsTable + " (operation_id,payload_hash) VALUES (?,?)")) {
                    statement.setString(1, mutation.id().toString()); statement.setString(2, hash); statement.executeUpdate();
                }
                connection.commit();
            } catch (Exception failure) { rollback(connection, failure); throw failure; }
        } catch (Exception failure) {
            // Do not guess whether commit took effect. A fresh connection verifies its durable receipt.
            try { if (verified(mutation.id(), hash)) return; }
            catch (Exception verification) { failure.addSuppressed(verification); }
            throw failure;
        }
        if (!verified(mutation.id(), hash)) throw new SQLException("Committed operation receipt could not be verified: " + mutation.id());
    }

    private long globalReset(Connection connection, boolean lock) throws SQLException {
        try (PreparedStatement statement = statement(connection,
                "SELECT reset_millis FROM " + stateTable + " WHERE id=1" + (lock ? " FOR UPDATE" : ""));
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) throw new SQLException("Missing persistence state");
            return rows.getLong(1);
        }
    }

    private boolean verified(UUID id, String hash) throws SQLException {
        try (Connection connection = pool.getConnection()) { return receipt(connection, id, hash); }
    }
    private boolean receipt(Connection connection, UUID id, String hash) throws SQLException {
        try (PreparedStatement statement = statement(connection, "SELECT payload_hash FROM " + operationsTable + " WHERE operation_id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return false;
                if (!hash.equals(rows.getString(1))) throw new SQLException("Operation ID reused with a different payload: " + id);
                return true;
            }
        }
    }

    private StorageView.PlayerClock playerClock(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement insert = statement(connection, "INSERT INTO " + clocksTable
                + " (uuid,reset_millis,metadata_millis) VALUES (?,0,0) ON DUPLICATE KEY UPDATE uuid=uuid")) {
            insert.setString(1, uuid.toString()); insert.executeUpdate();
        }
        try (PreparedStatement statement = statement(connection, "SELECT reset_millis,metadata_millis FROM " + clocksTable + " WHERE uuid=?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Missing player persistence state");
                return new StorageView.PlayerClock(rows.getLong(1), rows.getLong(2));
            }
        }
    }

    private void add(Connection connection, StorageMutation mutation, long globalCutoff) throws SQLException {
        try (PreparedStatement periods = statement(connection,
                "INSERT INTO " + playtimeTable + " (uuid,period_type,period_key,millis) VALUES (?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE millis=millis+VALUES(millis)")) {
            for (var entry : mutation.changes().entrySet()) {
                UUID uuid = entry.getKey(); var change = entry.getValue();
                var clock = playerClock(connection, uuid);
                try (PreparedStatement insert = statement(connection, "INSERT INTO " + playersTable
                        + " (uuid,name,last_seen_millis,hidden_from_leaderboards) VALUES (?,?,0,FALSE) ON DUPLICATE KEY UPDATE uuid=uuid")) {
                    insert.setString(1, uuid.toString()); insert.setString(2, change.name()); insert.executeUpdate();
                }
                if (change.metadataAt() > 0 && change.metadataAt() >= clock.metadataMillis()) {
                    try (PreparedStatement metadata = statement(connection, "UPDATE " + playersTable
                            + " SET name=?,hidden_from_leaderboards=? WHERE uuid=?")) {
                        metadata.setString(1, change.name()); metadata.setBoolean(2, change.hidden()); metadata.setString(3, uuid.toString()); metadata.executeUpdate();
                    }
                    try (PreparedStatement metadata = statement(connection, "UPDATE " + clocksTable + " SET metadata_millis=? WHERE uuid=?")) {
                        metadata.setLong(1, change.metadataAt()); metadata.setString(2, uuid.toString()); metadata.executeUpdate();
                    }
                }
                if (change.lastSeen() > 0) {
                    try (PreparedStatement seen = statement(connection, "UPDATE " + playersTable
                            + " SET last_seen_millis=GREATEST(COALESCE(last_seen_millis,0),?) WHERE uuid=?")) {
                        seen.setLong(1, change.lastSeen()); seen.setString(2, uuid.toString()); seen.executeUpdate();
                    }
                }
                long cutoff = Math.max(globalCutoff, clock.resetMillis()), total = 0;
                Map<String, Long> days = new HashMap<>(), months = new HashMap<>();
                for (TimeSlice slice : change.slices()) {
                    long millis = slice.millisAfter(cutoff);
                    if (millis == 0) continue;
                    days.merge(slice.day(), millis, Long::sum); months.merge(slice.month(), millis, Long::sum); total += millis;
                }
                for (var day : days.entrySet()) period(periods, uuid, "daily", day.getKey(), day.getValue());
                for (var month : months.entrySet()) period(periods, uuid, "monthly", month.getKey(), month.getValue());
                if (total > 0) period(periods, uuid, "alltime", "all", total);
            }
            periods.executeBatch();
        }
    }

    private void resetPlayer(Connection connection, UUID uuid, long at) throws SQLException {
        var clock = playerClock(connection, uuid);
        try (PreparedStatement delete = statement(connection, "DELETE FROM " + playtimeTable + " WHERE uuid=?");
             PreparedStatement reset = statement(connection, "UPDATE " + clocksTable + " SET reset_millis=? WHERE uuid=?")) {
            delete.setString(1, uuid.toString()); delete.executeUpdate();
            reset.setLong(1, Math.max(clock.resetMillis(), at)); reset.setString(2, uuid.toString()); reset.executeUpdate();
        }
    }
    private long databaseMillis(Connection connection) throws SQLException {
        try (PreparedStatement statement = statement(connection, "SELECT UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000.0"); ResultSet rows = statement.executeQuery()) {
            rows.next(); return rows.getLong(1);
        }
    }
    private void period(PreparedStatement statement, UUID uuid, String type, String key, long millis) throws SQLException {
        statement.setString(1, uuid.toString()); statement.setString(2, type);
        statement.setString(3, key); statement.setLong(4, millis); statement.addBatch();
    }
    private void rollback(Connection connection, Exception failure) {
        try { connection.rollback(); }
        catch (SQLException rollback) {
            failure.addSuppressed(rollback);
            // Never enable auto-commit on an unresolved transaction: that can commit partial writes.
            try { pool.evictConnection(connection); }
            catch (RuntimeException eviction) {
                failure.addSuppressed(eviction);
                try { connection.abort(Runnable::run); } catch (SQLException abort) { failure.addSuppressed(abort); }
            }
        }
    }
    @Override public void close() { if (pool != null) pool.close(); }
}
