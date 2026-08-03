package jp.jyn.jecon.config;

import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Properties;

public class MainConfig {
    public final BigDecimal defaultBalance;
    public final boolean createAccountOnJoin;

    public final String locale;

    public final boolean transactionLog;
    public final boolean hideNonPlayerAccounts;
    public final FormatConfig format;
    public final DatabaseConfig database;

    /**
     * @param config     config.yml のルートセクション
     * @param dataFolder SQLite ファイルの相対パス解決に使うプラグインデータフォルダ。
     *                   Bukkit の singleton を参照しないよう明示的に渡す（テスト可能性のため）。
     */
    public MainConfig(ConfigurationSection config, File dataFolder) {
        defaultBalance = new BigDecimal(config.getString("defaultBalance"));
        createAccountOnJoin = config.getBoolean("createAccountOnJoin");

        locale = config.getString("locale", "en").toLowerCase(Locale.ENGLISH);

        transactionLog = config.getBoolean("transactionLog", false);
        hideNonPlayerAccounts = config.getBoolean("hideNonPlayerAccounts", true);

        format = new FormatConfig(config.getConfigurationSection("format"));
        database = new DatabaseConfig(config.getConfigurationSection("database"), dataFolder);
    }

    public final static class FormatConfig {
        public enum MinorType {OMIT, ACCURATE, ASIS}

        public final String singularMajor;
        public final String pluralMajor;
        public final String singularMinor;
        public final String pluralMinor;
        public final FormatTemplate format;
        public final FormatTemplate formatZeroMinor;
        public final MinorType minorType;

        private FormatConfig(ConfigurationSection config) {
            singularMajor = config.getString("singularMajor");
            pluralMajor = config.getString("pluralMajor");
            singularMinor = config.getString("singularMinor");
            pluralMinor = config.getString("pluralMinor");
            format = new FormatTemplate(config.getString("format"));
            if (config.contains("formatZeroMinor")) {
                formatZeroMinor = new FormatTemplate(config.getString("formatZeroMinor"));
            } else {
                formatZeroMinor = format;
            }

            minorType = MinorType.valueOf(config.getString("minorType").toUpperCase(Locale.ENGLISH));
        }
    }

    public final static class DatabaseConfig {
        public final String url;
        public final String username;
        public final String password;
        public final String init;
        public final Properties properties = new Properties();

        public final int maximumPoolSize;
        public final int minimumIdle;
        public final long maxLifetime;
        public final long connectionTimeout;
        public final long idleTimeout;

        private DatabaseConfig(ConfigurationSection config, File dataFolder) {
            String type = config.getString("type", "").toLowerCase(Locale.ENGLISH);
            if (type.equals("sqlite")) {
                File file = new File(dataFolder, config.getString("sqlite.file"));
                //noinspection ResultOfMethodCallIgnored
                file.getParentFile().mkdirs();
                url = "jdbc:sqlite:" + file.getPath();
                username = null;
                password = null;
            } else if (type.equals("mysql")) {
                url = String.format("jdbc:mysql://%s/%s", config.getString("mysql.host"), config.getString("mysql" +
                    ".name"));
                username = config.getString("mysql.username");
                password = config.getString("mysql.password");
            } else {
                throw new IllegalArgumentException("Invalid value: database.type(config.yml)");
            }
            // SQLite は writer 同士を待ち合わせで解決させたいので busy_timeout を明示する。
            // driver 既定値に依存させない（Paper 同梱の sqlite-jdbc はバージョンが固定できない）。
            String defaultInit = type.equals("sqlite")
                ? "PRAGMA busy_timeout=5000"
                : "/* Jecon */SELECT 1";
            init = config.getString(type + ".init", defaultInit);
            String tmp = type + ".properties";
            if (config.contains(tmp)) {
                for (String key : config.getConfigurationSection(tmp).getKeys(false)) {
                    properties.put(key, config.getString(tmp + "." + key));
                }
            }

            maximumPoolSize = config.getInt("connectionPool.maximumPoolSize");
            minimumIdle = config.getInt("connectionPool.minimumIdle");
            maxLifetime = config.getLong("connectionPool.maxLifetime");
            connectionTimeout = config.getLong("connectionPool.connectionTimeout");
            idleTimeout = config.getLong("connectionPool.idleTimeout");
        }
    }
}
