package jp.jyn.jecon.testing;

import jp.jyn.jecon.config.MainConfig;
import jp.jyn.jecon.db.Database;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.DockerClientFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Testcontainers で立てた MySQL に接続する {@link DatabaseBackend}。
 *
 * <p>container はテスト実行全体で 1 つを共有し（起動が重いため）、テストごとに
 * 新しいスキーマを作って完全に分離する。スキーマが空なので
 * {@code MySQL.migration()} / {@code createTable()} も実運用と同じ経路を通る。
 */
public final class MySqlBackend implements DatabaseBackend {
    /** MySQL 8.4 LTS。query cache が削除された世代で確認したい。 */
    private static final String IMAGE = "mysql:8.4";

    private static final AtomicInteger SCHEMA_COUNTER = new AtomicInteger();
    private static volatile MySQLContainer<?> container;
    private static volatile Boolean available;

    /**
     * MySQL テストを実行できるか。
     *
     * <p>{@code -Djecon.test.mysql=false} で明示的に無効化できる。{@code auto}（既定）の
     * 場合は Docker が使えるかで判断する。CI では {@code true} を指定して、
     * Docker が無いことによる暗黙の skip を防ぐ。
     */
    public static boolean isAvailable() {
        String mode = System.getProperty("jecon.test.mysql", "auto");
        if ("false".equalsIgnoreCase(mode)) {
            return false;
        }
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (MySqlBackend.class) {
            if (available == null) {
                boolean docker = DockerClientFactory.instance().isDockerAvailable();
                if (!docker && "true".equalsIgnoreCase(mode)) {
                    throw new IllegalStateException(
                        "jecon.test.mysql=true was requested but Docker is not available");
                }
                available = docker;
            }
            return available;
        }
    }

    private static MySQLContainer<?> container() {
        MySQLContainer<?> existing = container;
        if (existing != null) {
            return existing;
        }
        synchronized (MySqlBackend.class) {
            if (container == null) {
                MySQLContainer<?> started = new MySQLContainer<>(IMAGE)
                    .withDatabaseName("jecon")
                    .withUsername("jecon")
                    .withPassword("jecon");
                started.start();
                // JVM 終了時に片付ける（Testcontainers の Ryuk でも回収されるが明示する）
                Runtime.getRuntime().addShutdownHook(new Thread(started::stop));
                container = started;
            }
            return container;
        }
    }

    @Override
    public Database connect(File dataFolder) {
        MySQLContainer<?> mysql = container();
        String schema = "jecon_test_" + SCHEMA_COUNTER.incrementAndGet();
        createSchema(mysql, schema);

        MainConfig config = TestFixture.mainConfig(dataFolder, yaml -> {
            yaml.set("database.type", "mysql");
            yaml.set("database.mysql.host", mysql.getHost() + ":" + mysql.getFirstMappedPort());
            yaml.set("database.mysql.name", schema);
            yaml.set("database.mysql.username", mysql.getUsername());
            yaml.set("database.mysql.password", mysql.getPassword());
            // init と properties はバンドルされた config.yml の既定値をそのまま使う。
            // 既定値が MySQL 8 で通らないなら、それはテストで落ちるべきこと。
        });
        return Database.connect(config.database, TestFixture.quietLogger());
    }

    /**
     * テスト専用のスキーマを作り、アプリ用ユーザーに権限を渡す。
     *
     * <p>アプリ用ユーザー（root ではない）は自分のスキーマ外に {@code CREATE DATABASE}
     * できないので、ここだけ root で行う。テストを非 root ユーザーで走らせるのは意図的で、
     * 実運用と同じ権限で DDL（{@code createTable} / migration）が通ることを確認するため。
     */
    private static void createSchema(MySQLContainer<?> mysql, String schema) {
        // MySQLContainer は MYSQL_ROOT_PASSWORD にも同じ password を設定する
        try (Connection connection = java.sql.DriverManager.getConnection(
            mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + schema + "`");
            statement.executeUpdate("GRANT ALL PRIVILEGES ON `" + schema + "`.* TO '"
                + mysql.getUsername() + "'@'%'");
        } catch (SQLException e) {
            throw new IllegalStateException("failed to create test schema " + schema, e);
        }
    }

    @Override
    public int retryableErrorCode() {
        return 1213;   // ER_LOCK_DEADLOCK
    }

    @Override
    public String displayName() {
        return "MySQL";
    }
}
