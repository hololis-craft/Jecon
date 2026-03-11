package jp.jyn.jecon.db.driver;

import com.zaxxer.hikari.HikariDataSource;
import jp.jyn.jecon.Jecon;
import jp.jyn.jecon.db.DBMigrationUtils;
import jp.jyn.jecon.db.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

public class SQLite extends Database {
    public SQLite(HikariDataSource hikari) {
        super(hikari);
    }

    @Override
    protected void createTable() {
        try (Connection connection = hikari.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `account` (" +
                    "`id`   INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," +
                    "`uuid` BLOB    NOT NULL UNIQUE," +
                    "`name` TEXT" +
                    ")"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS `account_name_index` ON `account` (`name`)"
            );
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `balance` (" +
                    "`id`      INTEGER NOT NULL PRIMARY KEY," +
                    "`balance` INTEGER NOT NULL" +
                    ")"
            );
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `transaction_log` (" +
                    "`id`        INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," +
                    "`timestamp` INTEGER NOT NULL," +
                    "`type`      INTEGER NOT NULL," +
                    "`uuid`      BLOB    NOT NULL," +
                    "`amount`    INTEGER NOT NULL" +
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

        Logger logger = Jecon.getInstance().getLogger();
        logger.info("Migrate SQLite");

        if ("1".equals(version)) {
            v1to2();
            version = DBMigrationUtils.getVersion(hikari);
        }
        if ("2".equals(version)) {
            v2to3();
            return;
        }
        logger.severe(DBMigrationUtils.MIGRATION_ERROR_1);
        logger.severe(DBMigrationUtils.MIGRATION_ERROR_2);
        throw new IllegalStateException(String.format(DBMigrationUtils.MIGRATION_EXCEPTION, version));
    }

    private void v1to2() {
        try (Connection connection = hikari.getConnection();
             Statement statement = connection.createStatement()) {
            // rename table
            statement.executeUpdate("ALTER TABLE `account` RENAME TO `account_old`");
            statement.executeUpdate("ALTER TABLE `balance` RENAME TO `balance_old`");
            statement.executeUpdate("DROP INDEX `nameindex`");

            // data copy
            createTable();
            DBMigrationUtils.v1copy2(connection);

            // update version
            statement.executeUpdate("DROP TABLE `meta`");
            DBMigrationUtils.getVersion(hikari);
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
}
