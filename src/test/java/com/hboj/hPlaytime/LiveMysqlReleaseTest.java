package com.hboj.hPlaytime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Opt-in staging integration. Uses an isolated generated prefix in the explicitly selected test database. */
class LiveMysqlReleaseTest {
    @Test void productionDdlLegacyRowsAndSharedOperationsWorkOnLiveMysql() throws Exception {
        String host = System.getenv("HPLAYTIME_TEST_MYSQL_HOST");
        String database = System.getenv("HPLAYTIME_TEST_MYSQL_DATABASE");
        assumeTrue(host != null && database != null, "Set HPLAYTIME_TEST_MYSQL_HOST and HPLAYTIME_TEST_MYSQL_DATABASE for staging");
        String prefix = "hpttest_" + UUID.randomUUID().toString().replace("-", "") + "x".repeat(15) + "_";
        var settings = PluginSettings.load(Map.of("storage", Map.of("mysql", Map.of(
            "host", host, "port", Integer.parseInt(System.getenv().getOrDefault("HPLAYTIME_TEST_MYSQL_PORT", "3306")),
            "database", database, "username", System.getenv().getOrDefault("HPLAYTIME_TEST_MYSQL_USER", "root"),
            "password", System.getenv().getOrDefault("HPLAYTIME_TEST_MYSQL_PASSWORD", ""),
            "use-ssl", Boolean.parseBoolean(System.getenv().getOrDefault("HPLAYTIME_TEST_MYSQL_SSL", "false")), "table-prefix", prefix)))).mysqlSettings();
        MysqlPlaytimeStorage a = new MysqlPlaytimeStorage(settings), b = new MysqlPlaytimeStorage(settings);
        try (HikariDataSource admin = new HikariDataSource(MysqlPlaytimeStorage.poolConfig(settings))) {
            UUID legacy = UUID.randomUUID();
            try {
                try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE " + prefix + "players (uuid CHAR(36) PRIMARY KEY,name VARCHAR(16) NOT NULL,last_seen_millis BIGINT NULL,hidden_from_leaderboards BOOLEAN NOT NULL DEFAULT FALSE,updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,INDEX name_index(name)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                    statement.execute("CREATE TABLE " + prefix + "playtime (uuid CHAR(36) NOT NULL,period_type VARCHAR(16) NOT NULL,period_key VARCHAR(32) NOT NULL,millis BIGINT NOT NULL DEFAULT 0,PRIMARY KEY(uuid,period_type,period_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                    statement.execute("INSERT INTO " + prefix + "players VALUES ('" + legacy + "','Legacy',123,TRUE,CURRENT_TIMESTAMP)");
                    statement.execute("INSERT INTO " + prefix + "playtime VALUES ('" + legacy + "','alltime','all',123)");
                }
                a.initialize(); b.initialize();
                assertEquals(123, a.loadPlayers().get(legacy).alltime()); assertTrue(a.loadPlayers().get(legacy).hidden());
                UUID uuid = UUID.randomUUID(); var first = TestData.add(uuid, 0, 100); var second = TestData.add(uuid, 0, 200);
                a.apply(first); b.apply(second); a.apply(first);
                assertEquals(300, a.loadPlayers().get(uuid).alltime()); assertEquals(300, b.loadPlayers().get(uuid).alltime());
                b.apply(StorageMutation.reset(uuid, 0)); a.apply(first);
                assertEquals(0, a.loadPlayers().get(uuid).alltime());
                long cutoff = a.loadView().cutoff(uuid);
                a.apply(TestData.add(uuid, cutoff - 50, cutoff + 50));
                assertEquals(50, b.loadPlayers().get(uuid).alltime());
            } finally {
                a.close(); b.close();
                try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
                    for (String table : MysqlPlaytimeStorage.protocolTables(prefix)) statement.execute("DROP TABLE IF EXISTS " + table);
                    statement.execute("DROP TABLE IF EXISTS " + prefix + "playtime");
                    statement.execute("DROP TABLE IF EXISTS " + prefix + "players");
                }
            }
        }
    }
}
