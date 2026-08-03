package jp.jyn.jecon.config;

import jp.jyn.jecon.Jecon;
import jp.jyn.jecon.config.migration.MainMigration;
import jp.jyn.jecon.config.migration.MessageMigration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

public class ConfigLoader {
    private static final String LEGACY_MESSAGE_FILE = "message.yml";
    private static final String[] BUNDLED_LOCALES = {"en", "ja"};
    private static final String DEFAULT_LOCALE = "en";

    private final Plugin plugin;
    private final File legacyMessageFile;

    private MainConfig mainConfig;
    private FileConfiguration messageYaml;
    private MessageConfig messageConfig;

    public ConfigLoader() {
        this.plugin = Jecon.getInstance();
        this.legacyMessageFile = new File(plugin.getDataFolder(), LEGACY_MESSAGE_FILE);
    }

    public void reloadConfig() {
        plugin.saveDefaultConfig();
        for (String locale : BUNDLED_LOCALES) {
            File file = new File(plugin.getDataFolder(), localeFileName(locale));
            if (!file.exists()) {
                plugin.saveResource(localeFileName(locale), false);
            }
        }

        if (mainConfig != null) {
            plugin.reloadConfig();
        }

        FileConfiguration mainYaml = plugin.getConfig();
        if (MainMigration.migration(mainYaml)) {
            plugin.saveConfig();
        }
        mainConfig = new MainConfig(mainYaml, plugin.getDataFolder());

        File messageFile = resolveMessageFile(mainConfig.locale);
        messageYaml = YamlConfiguration.loadConfiguration(messageFile);
        // Migration is only applied to the legacy message.yml. Locale-specific
        // files (message_en.yml / message_ja.yml) are shipped at CURRENT_VERSION
        // and rely on being re-extracted from the JAR when new keys are added.
        if (messageFile.equals(legacyMessageFile) && MessageMigration.migration(messageYaml)) {
            try {
                messageYaml.save(messageFile);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        messageConfig = new MessageConfig(messageYaml);
    }

    public MainConfig getMainConfig() {
        return mainConfig;
    }

    public MessageConfig getMessageConfig() {
        return messageConfig;
    }

    private File resolveMessageFile(String locale) {
        if (legacyMessageFile.exists()) {
            return legacyMessageFile;
        }
        File localeFile = new File(plugin.getDataFolder(), localeFileName(locale));
        if (localeFile.exists()) {
            return localeFile;
        }
        File fallback = new File(plugin.getDataFolder(), localeFileName(DEFAULT_LOCALE));
        if (!fallback.exists()) {
            plugin.saveResource(localeFileName(DEFAULT_LOCALE), false);
        }
        plugin.getLogger().warning("Unknown locale '" + locale + "', falling back to '" + DEFAULT_LOCALE + "'.");
        return fallback;
    }

    private static String localeFileName(String locale) {
        return "message_" + locale + ".yml";
    }
}
