package jp.jyn.jecon.db.driver;

import com.zaxxer.hikari.HikariDataSource;
import jp.jyn.jecon.db.DBMigrationUtils;
import jp.jyn.jecon.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

public class MySQL extends Database {
    /** {@code ER_LOCK_DEADLOCK}: InnoDB がデッドロックを検出して片方を巻き戻した。 */
    private static final int ER_LOCK_DEADLOCK = 1213;
    /** {@code ER_LOCK_WAIT_TIMEOUT}: 行ロック待ちがタイムアウトした。 */
    private static final int ER_LOCK_WAIT_TIMEOUT = 1205;

    public MySQL(HikariDataSource hikari, Logger logger) {
        super(hikari, logger);
    }

    @Override
    protected boolean isRetryable(SQLException e) {
        int code = e.getErrorCode();
        if (code == ER_LOCK_DEADLOCK || code == ER_LOCK_WAIT_TIMEOUT) {
            return true;
        }
        // 40001 = serialization failure
        return "40001".equals(e.getSQLState());
    }

    @Override
    protected void setOccurredAt(PreparedStatement statement, int index, Instant occurredAt) throws SQLException {
        statement.setTimestamp(index, Timestamp.from(occurredAt));
    }

    @Override
    public Instant readInstant(java.sql.ResultSet resultSet, String column) throws SQLException {
        Timestamp ts = resultSet.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    @Override
    protected void createTable() {
        try (Connection connection = hikari.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `account` (" +
                    "`id`         INT UNSIGNED     NOT NULL PRIMARY KEY AUTO_INCREMENT," +
                    "`uuid`       BINARY(16)       NOT NULL UNIQUE KEY," +
                    "`alias`      VARCHAR(97)      NOT NULL UNIQUE KEY," +
                    "`is_player`  TINYINT UNSIGNED NOT NULL," +
                    "`namespace`  VARCHAR(32)      NULL," +
                    "`created_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3)," +
                    "INDEX `idx_namespace` (`namespace`)" +
                    ")"
            );
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `balance` (" +
                    "`id`      INT    UNSIGNED NOT NULL PRIMARY KEY," +
                    "`balance` BIGINT NOT NULL" +
                    ")"
            );
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `transaction_log` (" +
                    "`id`          BIGINT UNSIGNED NOT NULL PRIMARY KEY AUTO_INCREMENT," +
                    "`occurred_at` DATETIME(3)     NOT NULL," +
                    "`source`      VARCHAR(32)     NOT NULL," +
                    "`from_id`     INT UNSIGNED    NULL," +
                    "`to_id`       INT UNSIGNED    NULL," +
                    "`amount`      BIGINT          NOT NULL," +
                    "`leg_label`   VARCHAR(32)     NOT NULL DEFAULT 'primary'," +
                    "`batch_id`    BIGINT UNSIGNED NULL," +
                    "`actor_uuid`  BINARY(16)      NULL," +
                    "`metadata`    JSON            NULL," +
                    "INDEX `idx_occurred`    (`occurred_at`)," +
                    "INDEX `idx_source_time` (`source`, `occurred_at`)," +
                    "INDEX `idx_from_time`   (`from_id`, `occurred_at` DESC)," +
                    "INDEX `idx_to_time`     (`to_id`,   `occurred_at` DESC)," +
                    "INDEX `idx_batch`       (`batch_id`)" +
                    ")"
            );
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `account_member` (" +
                    "`account_id`  INT UNSIGNED NOT NULL," +
                    "`member_uuid` BINARY(16)   NOT NULL," +
                    "`permissions` INT UNSIGNED NOT NULL DEFAULT 0," +
                    "PRIMARY KEY (`account_id`, `member_uuid`)," +
                    "INDEX `idx_member` (`member_uuid`)" +
                    ")"
            );
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void migration() {
        String version = DBMigrationUtils.getVersion(hikari);

        if (version.equals(DBMigrationUtils.CURRENT_VERSION)) {
            return;
        }

        logger.info("Migrate MySQL");

        if (version.equals("1")) {
            v1to2(v1prefix());
            version = DBMigrationUtils.getVersion(hikari);
        }
        if (version.equals("2")) {
            v2to3();
            version = readVersion();
        }
        if (version.equals("3")) {
            v3to4();
            return;
        }
        logger.severe(DBMigrationUtils.MIGRATION_ERROR_1);
        logger.severe(DBMigrationUtils.MIGRATION_ERROR_2);
        throw new IllegalStateException(String.format(DBMigrationUtils.MIGRATION_EXCEPTION, version));
    }

    private String readVersion() {
        try (Connection connection = hikari.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT `value` FROM `meta` WHERE `key`='dbversion'")) {
            return rs.next() ? rs.getString(1) : DBMigrationUtils.CURRENT_VERSION;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String v1prefix() {
        String prefix = System.getProperty("jecon.prefix");
        if (prefix != null) {
            return prefix;
        }

        try (Connection connection = hikari.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW TABLES")) {
            while (resultSet.next()) {
                String table = resultSet.getString(1);
                if (table.endsWith("account")) {
                    if (prefix != null) {
                                        logger.severe(DBMigrationUtils.MIGRATION_ERROR_1);
                        logger.severe("This database seems to be used by multiple Jecon.");
                        logger.severe("Since the prefix has been deleted, it is not possible to use one database with multiple Jecon.");
                        logger.severe("To continue processing, start up the server with -Djecon.prefix=<prefix>.");
                        throw new IllegalStateException("");
                    }
                    prefix = table.substring(0, table.length() - "account".length());
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return prefix;
    }

    private void v1to2(String prefix) {
        logger.info("prefix: " + prefix);
        try (Connection connection = hikari.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("RENAME TABLE " +
                "`" + prefix + "account` TO `account_old`," +
                "`" + prefix + "balance` TO `balance_old`"
            );

            // data copy
            createTableV3();
            DBMigrationUtils.v1copy2(connection);

            // update version
            statement.executeUpdate("DROP TABLE `meta`");
            DBMigrationUtils.getVersion(hikari);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** v1→v2 マイグレーション後の中間スキーマ（`name` カラム無し）。 */
    private void createTableV3() {
        try (Connection connection = hikari.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `account` (" +
                    "`id`   INT UNSIGNED NOT NULL PRIMARY KEY AUTO_INCREMENT," +
                    "`uuid` BINARY(16)   NOT NULL UNIQUE KEY" +
                    ")"
            );
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `balance` (" +
                    "`id`      INT    UNSIGNED NOT NULL PRIMARY KEY," +
                    "`balance` BIGINT NOT NULL" +
                    ")"
            );
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void v2to3() {
        try (Connection connection = hikari.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE `account` ADD COLUMN `name` VARCHAR(16) NULL");
            statement.executeUpdate("CREATE INDEX `account_name_index` ON `account` (`name`)");
            statement.executeUpdate("UPDATE `meta` SET `value`='3' WHERE `key`='dbversion'");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * v3→v4:
     * <ul>
     *   <li>{@code account}: {@code name} を {@code alias} に整形。{@code is_player}/{@code namespace}/{@code created_at} を追加</li>
     *   <li>{@code transaction_log}: 旧テーブルを {@code transaction_log_v1} にリネームし、新スキーマで作り直す</li>
     *   <li>{@code account_member}: 新規作成</li>
     * </ul>
     */
    private void v3to4() {
        logger.info("Migrating account/transaction_log to v4 schema");
        try (Connection connection = hikari.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate(
                        "ALTER TABLE `account`" +
                            "  ADD COLUMN `alias`      VARCHAR(97)      NULL AFTER `uuid`," +
                            "  ADD COLUMN `is_player`  TINYINT UNSIGNED NOT NULL DEFAULT 1," +
                            "  ADD COLUMN `namespace`  VARCHAR(32)      NULL," +
                            "  ADD COLUMN `created_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3)"
                    );
                }

                populateAliasesV3to4(connection);

                try (Statement statement = connection.createStatement()) {
                    // name カラムと関連インデックスを撤去し、alias 制約を確定
                    try {
                        statement.executeUpdate("DROP INDEX `account_name_index` ON `account`");
                    } catch (SQLException ignored) {
                        // インデックスが存在しない環境向けフォールバック
                    }
                    statement.executeUpdate("ALTER TABLE `account` DROP COLUMN `name`");
                    statement.executeUpdate("ALTER TABLE `account` MODIFY COLUMN `alias` VARCHAR(97) NOT NULL");
                    statement.executeUpdate("ALTER TABLE `account` ADD UNIQUE KEY `uk_alias` (`alias`)");
                    statement.executeUpdate("CREATE INDEX `idx_namespace` ON `account` (`namespace`)");

                    // 旧 transaction_log を退避
                    try {
                        statement.executeUpdate("DROP TABLE IF EXISTS `transaction_log_v1`");
                        statement.executeUpdate("RENAME TABLE `transaction_log` TO `transaction_log_v1`");
                    } catch (SQLException ignored) {
                        // 旧テーブルが無ければスキップ
                    }
                }

                // 新 transaction_log と account_member を作成
                createTable();

                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE `meta` SET `value`='4' WHERE `key`='dbversion'");
                }

                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed v3→v4 migration", e);
        }
    }

    /**
     * 既存レコードの {@code name} を {@code alias} に写し取る。重複や NULL は
     * hex(UUID) にフォールバックし、UNIQUE 制約を満たす。
     */
    private void populateAliasesV3to4(Connection connection) throws SQLException {
        Set<String> used = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                 "SELECT `id`, `name`, LOWER(HEX(`uuid`)) AS `hex` FROM `account`"
             );
             PreparedStatement ps = connection.prepareStatement(
                 "UPDATE `account` SET `alias`=? WHERE `id`=?"
             )) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String name = rs.getString("name");
                String hex = rs.getString("hex");

                String alias;
                if (name != null && !name.isEmpty() && used.add(name.toLowerCase())) {
                    alias = name;
                } else {
                    alias = hex;
                    used.add(hex);
                }
                ps.setString(1, alias);
                ps.setInt(2, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
