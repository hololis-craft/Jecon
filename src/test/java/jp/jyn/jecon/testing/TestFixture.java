package jp.jyn.jecon.testing;

import jp.jyn.jecon.account.Aliases;
import jp.jyn.jecon.config.MainConfig;
import jp.jyn.jecon.db.Database;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bukkit サーバを起動せずに {@link Database} と {@link MainConfig} を組み立てるテスト用ヘルパ。
 *
 * <p>{@code src/main/resources/config.yml} を実際に読むので、既定値の変更もテストに反映される。
 * {@link YamlConfiguration} は純 Java 実装なので server instance を必要としない。
 */
public final class TestFixture {
    private TestFixture() {}

    /** テストログは基本的に黙らせる（migration の info が大量に出るのを防ぐ）。 */
    public static Logger quietLogger() {
        Logger logger = Logger.getLogger("JeconTest");
        logger.setLevel(Level.WARNING);
        return logger;
    }

    /**
     * バンドルされた config.yml をそのまま読み込んだ {@link MainConfig} を返す。
     *
     * @param dataFolder SQLite ファイルを置くディレクトリ（JUnit の {@code @TempDir} を渡す）
     */
    public static MainConfig mainConfig(File dataFolder) {
        return mainConfig(dataFolder, YamlConfiguration::loadConfiguration);
    }

    /**
     * config.yml を読み込んだ後に加工してから {@link MainConfig} を組み立てる。
     * connectionPool の設定を上書きするテストなどで使う。
     */
    public static MainConfig mainConfig(File dataFolder, java.util.function.Function<Reader, YamlConfiguration> loader) {
        try (InputStream in = TestFixture.class.getClassLoader().getResourceAsStream("config.yml")) {
            if (in == null) {
                throw new IllegalStateException("config.yml not found on the test classpath");
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return new MainConfig(loader.apply(reader), dataFolder);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 一時ディレクトリ上の SQLite に接続した {@link Database} を返す。 */
    public static Database sqlite(File dataFolder) {
        return Database.connect(mainConfig(dataFolder).database, quietLogger());
    }

    /**
     * {@code Jecon.ensureLegacyAccounts()} 相当の system 口座を用意する。
     *
     * <p>{@code BalanceRepository} / Vault bridge 経由の入出金はこれらを対向口座に使うため、
     * 用意しないと書き込みが一律 false になる。
     */
    public static void ensureSystemAccounts(Database db) {
        for (String alias : new String[]{
            "system:legacy_source", "system:legacy_sink",
            "system:vault_bridge", "system:vault_unlocked_bridge"
        }) {
            UUID uuid = Aliases.uuidFromAlias(alias);
            if (db.resolveId(uuid).isPresent()) {
                continue;
            }
            try {
                db.insertAccount(uuid, alias, false, Aliases.namespaceOf(alias));
            } catch (SQLException e) {
                throw new IllegalStateException("failed to create system account: " + alias, e);
            }
            db.createBalance(db.resolveId(uuid).orElseThrow(), 0L);
        }
    }
}
