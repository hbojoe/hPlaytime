package com.hboj.hPlaytime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbc.JdbcConnection;
import org.junit.jupiter.api.Test;

/** Production transaction SQL against H2 MySQL mode; live MySQL still belongs in staging. */
class MysqlPlaytimeStorageTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path folder;

    @Test void recoveryCheckpointSurvivesUncertainCommitAndIsRemovedOnlyAfterResetIsVerified() throws Exception {
        try (HikariDataSource real = pool(2)) {
            MysqlPlaytimeStorage observer = storage(real); HikariDataSource faultyPool = mock(HikariDataSource.class);
            Connection committed = spy(real.getConnection());
            doAnswer(call -> { call.callRealMethod(); throw new SQLException("commit acknowledged too late"); }).when(committed).commit();
            when(faultyPool.getConnection()).thenReturn(committed).thenThrow(new SQLException("verification unavailable"))
                .thenAnswer(ignored -> real.getConnection());
            MysqlPlaytimeStorage writer = new MysqlPlaytimeStorage(TestData.settings().mysqlSettings(), faultyPool);
            PlaytimeStorage backend = new PlaytimeStorage() {
                public void initialize() {}
                public StorageView loadView() throws Exception { return observer.loadView(); }
                public void apply(StorageMutation mutation) throws Exception { writer.apply(mutation); }
                public void close() {}
            };
            PersistenceService service = new PersistenceService(folder, ignored -> backend);
            try {
                service.activate(service.prepare(TestData.settings())); UUID uuid = UUID.randomUUID();
                StorageMutation addition = TestData.add(uuid, 0, 100);
                assertThrows(SQLException.class, () -> service.persist(new PersistenceService.Batch(List.of(addition), Map.of())));
                try (var files = java.nio.file.Files.list(folder)) {
                    assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("recovery-")));
                }
                service.persist(new PersistenceService.Batch(List.of(addition, StorageMutation.reset(uuid, 0)), Map.of()));
                assertEquals(0, observer.loadPlayers().get(uuid).alltime());
                try (var files = java.nio.file.Files.list(folder)) {
                    assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("recovery-")));
                }
            } finally { service.close(); }
        }
    }
    @Test void configuredPoolAndDriverTimeoutsAreFiniteAndClamped() {
        var settings = PluginSettings.load(Map.of("storage", Map.of("mysql", Map.of(
            "maximum-pool-size", 10000, "connection-timeout-millis", 0,
            "validation-timeout-millis", 1000000, "socket-timeout-millis", 0, "query-timeout-seconds", -1)))).mysqlSettings();
        HikariConfig config = MysqlPlaytimeStorage.poolConfig(settings);
        assertEquals(8, config.getMaximumPoolSize()); assertEquals(500, config.getConnectionTimeout());
        assertTrue(config.getValidationTimeout() < config.getConnectionTimeout());
        assertEquals(250, config.getDataSourceProperties().get("socketTimeout")); assertEquals(1, settings.queryTimeoutSeconds());
    }

    @Test void twoIndependentInstancesContributing100And200ProduceExactly300() throws Exception {
        try (HikariDataSource pool = pool(1)) {
            MysqlPlaytimeStorage a = storage(pool), b = storage(pool);
            a.loadView(); b.loadView(); UUID uuid = UUID.randomUUID();
            StorageMutation first = TestData.add(uuid, 0, 100), second = TestData.add(uuid, 0, 200);
            a.apply(first); b.apply(second); a.apply(first); b.apply(second);
            for (MysqlPlaytimeStorage server : List.of(a, b)) {
                PlayerData shared = server.loadPlayers().get(uuid);
                assertEquals(300, shared.alltime());
                assertEquals(300, shared.daily().get("2026-10-27")); assertEquals(300, shared.monthly().get("2026-10"));
            }
        }
    }

    @Test void uncertainCommitFollowedByResetCannotSkipDatabaseChanges() throws Exception {
        try (HikariDataSource real = pool(1)) {
            MysqlPlaytimeStorage observer = storage(real);
            HikariDataSource faultyPool = mock(HikariDataSource.class);
            Connection committed = spy(real.getConnection());
            doAnswer(call -> { call.callRealMethod(); throw new SQLException("commit succeeded; acknowledgement lost"); }).when(committed).commit();
            when(faultyPool.getConnection()).thenReturn(committed)
                .thenThrow(new SQLException("receipt verification unavailable"))
                .thenAnswer(ignored -> real.getConnection());
            MysqlPlaytimeStorage faulty = new MysqlPlaytimeStorage(TestData.settings().mysqlSettings(), faultyPool);
            UUID uuid = UUID.randomUUID();
            StorageMutation addition = TestData.add(uuid, 0, 100);
            assertThrows(SQLException.class, () -> faulty.apply(addition));
            assertEquals(100, observer.loadPlayers().get(uuid).alltime());
            faulty.apply(StorageMutation.reset(uuid, 0));
            faulty.apply(addition); // Delayed retry must neither duplicate time nor resurrect pre-reset time.
            PlayerData reset = observer.loadPlayers().get(uuid);
            assertEquals(0, reset.alltime()); assertTrue(reset.daily().isEmpty()); assertTrue(reset.monthly().isEmpty());
        }
    }

    @Test void lostCommitAcknowledgementIsResolvedByDurableReceiptAndRetryIsIdempotent() throws Exception {
        try (HikariDataSource real = pool(1)) {
            MysqlPlaytimeStorage observer = storage(real); HikariDataSource faultyPool = mock(HikariDataSource.class);
            Connection committed = spy(real.getConnection());
            doAnswer(call -> { call.callRealMethod(); throw new SQLException("lost acknowledgement"); }).when(committed).commit();
            when(faultyPool.getConnection()).thenReturn(committed).thenAnswer(ignored -> real.getConnection());
            MysqlPlaytimeStorage writer = new MysqlPlaytimeStorage(TestData.settings().mysqlSettings(), faultyPool);
            UUID uuid = UUID.randomUUID(); StorageMutation addition = TestData.add(uuid, 0, 100);
            writer.apply(addition); writer.apply(addition);
            assertEquals(100, observer.loadPlayers().get(uuid).alltime());
        }
    }

    @Test void remotePlayerAndGlobalResetsClipStaleIntervalsButKeepPostResetTime() throws Exception {
        try (HikariDataSource pool = pool(1)) {
            MysqlPlaytimeStorage a = storage(pool), b = storage(pool); UUID uuid = UUID.randomUUID();
            a.apply(TestData.add(uuid, 0, 100)); b.loadView();
            a.apply(StorageMutation.reset(uuid, 0));
            long cutoff = a.loadView().cutoff(uuid);
            b.apply(TestData.add(uuid, cutoff - 50, cutoff + 50));
            assertEquals(50, a.loadPlayers().get(uuid).alltime());
            a.apply(StorageMutation.reset(null, 0));
            long global = a.loadView().resetMillis();
            b.apply(TestData.add(uuid, global - 100, global + 200));
            assertEquals(200, a.loadPlayers().get(uuid).alltime());
        }
    }

    @Test void metadataAndLastSeenDoNotRegressWhenAnOlderOperationArrivesLate() throws Exception {
        try (HikariDataSource pool = pool(1)) {
            MysqlPlaytimeStorage storage = storage(pool); UUID uuid = UUID.randomUUID();
            storage.apply(StorageMutation.add(Map.of(uuid, new StorageMutation.Change("New", true, 200, 300, List.of()))));
            storage.apply(StorageMutation.add(Map.of(uuid, new StorageMutation.Change("Old", false, 100, 100, List.of()))));
            PlayerData player = storage.loadPlayers().get(uuid);
            assertEquals("New", player.name()); assertTrue(player.hidden()); assertEquals(300, player.lastSeen());
        }
    }

    @Test void reusedOperationIdWithDifferentPayloadIsRejected() throws Exception {
        try (HikariDataSource pool = pool(1)) {
            MysqlPlaytimeStorage storage = storage(pool); UUID uuid = UUID.randomUUID();
            StorageMutation first = TestData.add(uuid, 0, 100); storage.apply(first);
            StorageMutation changed = new StorageMutation(first.id(), first.kind(), TestData.add(uuid, 0, 200).changes(), null, 0);
            assertThrows(SQLException.class, () -> storage.apply(changed));
            assertEquals(100, storage.loadPlayers().get(uuid).alltime());
        }
    }

    @Test void concurrentInstancesSerializeAddsWithoutLosingAnyContribution() throws Exception {
        try (HikariDataSource pool = pool(2)) {
            MysqlPlaytimeStorage a = storage(pool), b = storage(pool); UUID uuid = UUID.randomUUID();
            var executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
            try {
                var one = executor.submit(() -> { start.await(); for (int i = 0; i < 50; i++) a.apply(TestData.add(uuid, 0, 1)); return true; });
                var two = executor.submit(() -> { start.await(); for (int i = 0; i < 50; i++) b.apply(TestData.add(uuid, 0, 1)); return true; });
                start.countDown(); one.get(30, TimeUnit.SECONDS); two.get(30, TimeUnit.SECONDS);
                assertEquals(100, a.loadPlayers().get(uuid).alltime());
            } finally { executor.shutdownNow(); }
        }
    }

    @Test void poolReusesPhysicalConnectionsAndLegacyRowsLoadWithoutRewritingThem() throws Exception {
        try (HikariDataSource pool = pool(1)) {
            MysqlPlaytimeStorage storage = storage(pool); UUID uuid = UUID.randomUUID(); JdbcConnection physical;
            try (Connection connection = pool.getConnection()) {
                physical = connection.unwrap(JdbcConnection.class);
                try (var insert = connection.prepareStatement("INSERT INTO hplaytime_players VALUES (?,?,?,?)")) {
                    insert.setString(1, uuid.toString()); insert.setString(2, "Legacy"); insert.setLong(3, 123); insert.setBoolean(4, true); insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO hplaytime_playtime VALUES (?,?,?,?)")) {
                    for (String type : List.of("daily", "monthly", "alltime")) {
                        insert.setString(1, uuid.toString()); insert.setString(2, type);
                        insert.setString(3, type.equals("daily") ? "2026-10-27" : type.equals("monthly") ? "2026-10" : "all");
                        insert.setLong(4, 100); insert.executeUpdate();
                    }
                }
            }
            try (Connection connection = pool.getConnection()) { assertSame(physical, connection.unwrap(JdbcConnection.class)); }
            PlayerData legacy = storage.loadPlayers().get(uuid);
            assertEquals(100, legacy.alltime()); assertEquals("Legacy", legacy.name()); assertTrue(legacy.hidden()); assertEquals(123, legacy.lastSeen());
            storage.apply(TestData.add(uuid, 0, 50));
            assertEquals(150, storage.loadPlayers().get(uuid).alltime());
        }
    }

    @Test void failedRollbackEvictsTheConnectionWithoutImplicitlyCommittingPartialWrites() throws Exception {
        try (HikariDataSource real = pool(1)) {
            MysqlPlaytimeStorage observer = storage(real); HikariDataSource faultyPool = mock(HikariDataSource.class);
            Connection connection = spy(real.getConnection());
            doAnswer(call -> {
                java.sql.PreparedStatement statement = (java.sql.PreparedStatement) call.callRealMethod();
                if (((String) call.getArgument(0)).startsWith("INSERT INTO hplaytime_playtime")) {
                    statement = spy(statement);
                    doAnswer(batch -> { batch.callRealMethod(); throw new SQLException("partial batch failure"); }).when(statement).executeBatch();
                }
                return statement;
            }).when(connection).prepareStatement(anyString());
            doThrow(new SQLException("rollback unavailable")).when(connection).rollback();
            when(faultyPool.getConnection()).thenReturn(connection).thenAnswer(ignored -> real.getConnection());
            doAnswer(call -> { real.evictConnection(call.getArgument(0)); return null; }).when(faultyPool).evictConnection(connection);
            MysqlPlaytimeStorage writer = new MysqlPlaytimeStorage(TestData.settings().mysqlSettings(), faultyPool);
            UUID uuid = UUID.randomUUID(); StorageMutation mutation = TestData.add(uuid, 0, 100);
            assertThrows(SQLException.class, () -> writer.apply(mutation));
            verify(faultyPool).evictConnection(connection); verify(connection, never()).setAutoCommit(true);
            writer.apply(mutation);
            assertEquals(100, observer.loadPlayers().get(uuid).alltime());
        }
    }

    @Test void longestLegacyPrefixStillProducesValidMysqlIdentifiers() throws Exception {
        try (HikariDataSource pool = pool(1)) {
            String prefix = "p".repeat(56);
            var settings = PluginSettings.load(Map.of("storage", Map.of("mysql", Map.of("table-prefix", prefix)))).mysqlSettings();
            MysqlPlaytimeStorage storage = new MysqlPlaytimeStorage(settings, pool);
            try (Connection connection = pool.getConnection()) {
                storage.initializeProtocol(connection);
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='PUBLIC'")) {
                    int count = 0;
                    while (rows.next()) { assertTrue(rows.getString(1).length() <= 64); count++; }
                    assertEquals(3, count);
                }
            }
        }
    }

    static HikariDataSource pool(int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        config.setMaximumPoolSize(size); config.setMinimumIdle(0); config.setConnectionTimeout(5000); config.setValidationTimeout(250);
        return new HikariDataSource(config);
    }
    static MysqlPlaytimeStorage storage(HikariDataSource pool) throws Exception {
        MysqlPlaytimeStorage storage = new MysqlPlaytimeStorage(TestData.settings().mysqlSettings(), pool);
        try (Connection connection = pool.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS hplaytime_players(uuid CHAR(36) PRIMARY KEY,name VARCHAR(16),last_seen_millis BIGINT,hidden_from_leaderboards BOOLEAN)");
            statement.execute("CREATE TABLE IF NOT EXISTS hplaytime_playtime(uuid CHAR(36),period_type VARCHAR(16),period_key VARCHAR(32),millis BIGINT,PRIMARY KEY(uuid,period_type,period_key))");
            storage.initializeProtocol(connection);
        }
        return storage;
    }
}
