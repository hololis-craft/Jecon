package jp.jyn.jecon.db.driver;

import com.zaxxer.hikari.HikariDataSource;
import jp.jyn.jecon.db.DBMigrationUtils;
import jp.jyn.jecon.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

public class SQLite extends Database {
    public SQLite(HikariDataSource hikari, Logger logger) {
        super(hikari, logger);
    }

    /** {@code SQLITE_CONSTRAINT}。拡張コードは {@code 19 | (n << 8)} なので下位 1 バイトで判定する。 */
    private static final int SQLITE_CONSTRAINT = 19;

    @Override
    protected boolean supportsSelectForUpdate() {
        return false;
    }

    /** {@code SQLITE_BUSY} / {@code SQLITE_LOCKED}。write lock が取れなかった場合。 */
    private static final int SQLITE_BUSY = 5;
    private static final int SQLITE_LOCKED = 6;

    @Override
    protected boolean isConstraintViolation(SQLException e) {
        // sqlite-jdbc は SQLState を設定せず vendor code のみを返す。
        if ((e.getErrorCode() & 0xFF) == SQLITE_CONSTRAINT) {
            return true;
        }
        return super.isConstraintViolation(e);
    }

    @Override
    protected boolean isRetryable(SQLException e) {
        int primary = e.getErrorCode() & 0xFF;
        return primary == SQLITE_BUSY || primary == SQLITE_LOCKED;
    }

    /**
     * {@code BEGIN IMMEDIATE} でトランザクションを開始する。
     *
     * <p>{@code setAutoCommit(false)} は deferred BEGIN になるため、SELECT で read lock を
     * 取ってから UPDATE で write lock に昇格する形になる。この昇格はデッドロックし得るので
     * SQLite は busy handler を呼ばずに即 {@code SQLITE_BUSY} を返す（busy_timeout が効かない）。
     * 最初から write lock を取れば待ち合わせで解決できる。
     *
     * <p>autoCommit は true のまま維持し、トランザクション境界は生 SQL で制御する。
     * {@code setAutoCommit(false)} と併用すると driver 自身の BEGIN と二重になる。
     */
    @Override
    protected void beginTx(Connection connection) throws SQLException {
        execute(connection, "BEGIN IMMEDIATE");
    }

    @Override
    protected void commitTx(Connection connection) throws SQLException {
        execute(connection, "COMMIT");
    }

    @Override
    protected void rollbackTx(Connection connection) throws SQLException {
        execute(connection, "ROLLBACK");
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * WAL に切り替える。reader が writer をブロックしなくなるので、複数接続から
     * 並行アクセスする前提では必須。DB ファイルに永続する設定なので毎回設定しても無害。
     */
    private void enableWriteAheadLog() {
        try (Connection connection = hikari.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA journal_mode=WAL")) {
            String mode = rs.next() ? rs.getString(1) : "?";
            if (!"wal".equalsIgnoreCase(mode)) {
                // ネットワークファイルシステム上などでは WAL に切り替えられないことがある
                logger.warning("Could not enable SQLite WAL mode (journal_mode=" + mode + "). " +
                    "Concurrent access will serialize more aggressively.");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void createTable() {
        enableWriteAheadLog();
        try (Connection connection = hikari.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `account` (" +
                    "`id`         INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," +
                    "`uuid`       BLOB    NOT NULL UNIQUE," +
                    "`alias`      TEXT    NOT NULL UNIQUE," +
                    "`is_player`  INTEGER NOT NULL," +
                    "`namespace`  TEXT," +
                    "`created_at` INTEGER NOT NULL" +
                    ")"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS `idx_namespace` ON `account` (`namespace`)"
            );
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `balance` (" +
                    "`id`      INTEGER NOT NULL PRIMARY KEY," +
                    "`balance` INTEGER NOT NULL" +
                    ")"
            );
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `transaction_log` (" +
                    "`id`          INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," +
                    "`occurred_at` INTEGER NOT NULL," +
                    "`source`      TEXT    NOT NULL," +
                    "`from_id`     INTEGER," +
                    "`to_id`       INTEGER," +
                    "`amount`      INTEGER NOT NULL," +
                    "`leg_label`   TEXT    NOT NULL DEFAULT 'primary'," +
                    "`batch_id`    INTEGER," +
                    "`actor_uuid`  BLOB," +
                    "`metadata`    TEXT" +
                    ")"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS `idx_occurred`    ON `transaction_log` (`occurred_at`)"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS `idx_source_time` ON `transaction_log` (`source`, `occurred_at`)"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS `idx_from_time`   ON `transaction_log` (`from_id`, `occurred_at` DESC)"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS `idx_to_time`     ON `transaction_log` (`to_id`,   `occurred_at` DESC)"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS `idx_batch`       ON `transaction_log` (`batch_id`)"
            );
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `account_member` (" +
                    "`account_id`  INTEGER NOT NULL," +
                    "`member_uuid` BLOB    NOT NULL," +
                    "`permissions` INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY (`account_id`, `member_uuid`)" +
                    ")"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS `idx_account_member_member` ON `account_member` (`member_uuid`)"
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

        logger.info("Migrate SQLite");

        if ("1".equals(version)) {
            v1to2();
            version = DBMigrationUtils.getVersion(hikari);
        }
        if ("2".equals(version)) {
            v2to3();
            version = readVersion();
        }
        if ("3".equals(version)) {
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

    private void v1to2() {
        try (Connection connection = hikari.getConnection();
             Statement statement = connection.createStatement()) {
            // rename table
            statement.executeUpdate("ALTER TABLE `account` RENAME TO `account_old`");
            statement.executeUpdate("ALTER TABLE `balance` RENAME TO `balance_old`");
            statement.executeUpdate("DROP INDEX `nameindex`");

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

    private void createTableV3() {
        try (Connection connection = hikari.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `account` (" +
                    "`id`   INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," +
                    "`uuid` BLOB    NOT NULL UNIQUE" +
                    ")"
            );
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `balance` (" +
                    "`id`      INTEGER NOT NULL PRIMARY KEY," +
                    "`balance` INTEGER NOT NULL" +
                    ")"
            );
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void v2to3() {
        try (Connection connection = hikari.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE `account` ADD COLUMN `name` TEXT");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS `account_name_index` ON `account` (`name`)");
            statement.executeUpdate("UPDATE `meta` SET `value`='3' WHERE `key`='dbversion'");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * v3→v4: SQLite は列削除・型変更を素直にはできないため、account をテーブル再構築で置換する。
     * balance はスキーマ変更が無いので touch しない。
     */
    private void v3to4() {
        logger.info("Migrating account/transaction_log to v4 schema");
        try (Connection connection = hikari.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    // account 側: 旧テーブルをリネームし、新スキーマで再作成 → データを写す
                    try {
                        statement.executeUpdate("DROP INDEX IF EXISTS `account_name_index`");
                    } catch (SQLException ignored) {
                        // 無い環境向けフォールバック
                    }
                    statement.executeUpdate("ALTER TABLE `account` RENAME TO `account_old`");
                }

                createAccountV4(connection);
                copyAccountsV3to4(connection);

                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DROP TABLE `account_old`");

                    // 旧 transaction_log は退避
                    try {
                        statement.executeUpdate("DROP TABLE IF EXISTS `transaction_log_v1`");
                        statement.executeUpdate("ALTER TABLE `transaction_log` RENAME TO `transaction_log_v1`");
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

    private void createAccountV4(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TABLE `account` (" +
                    "`id`         INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," +
                    "`uuid`       BLOB    NOT NULL UNIQUE," +
                    "`alias`      TEXT    NOT NULL UNIQUE," +
                    "`is_player`  INTEGER NOT NULL," +
                    "`namespace`  TEXT," +
                    "`created_at` INTEGER NOT NULL" +
                    ")"
            );
        }
    }

    private void copyAccountsV3to4(Connection connection) throws SQLException {
        long now = System.currentTimeMillis();
        Set<String> used = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                 "SELECT `id`, `uuid`, `name` FROM `account_old`"
             );
             PreparedStatement ps = connection.prepareStatement(
                 "INSERT INTO `account` (`id`, `uuid`, `alias`, `is_player`, `namespace`, `created_at`) VALUES (?,?,?,1,NULL,?)"
             )) {
            while (rs.next()) {
                int id = rs.getInt(1);
                byte[] uuidBytes = rs.getBytes(2);
                String name = rs.getString(3);
                String hex = toHex(uuidBytes);

                String alias;
                if (name != null && !name.isEmpty() && used.add(name.toLowerCase())) {
                    alias = name;
                } else {
                    alias = hex;
                    used.add(hex);
                }

                ps.setInt(1, id);
                ps.setBytes(2, uuidBytes);
                ps.setString(3, alias);
                ps.setLong(4, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
