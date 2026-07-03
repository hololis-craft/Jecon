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
    private static final String MESSAGE_FILE = "message.yml";

    private final Plugin plugin;
    private final File messageFile;

    private MainConfig mainConfig;
    private FileConfiguration messageYaml;
    private MessageConfig messageConfig;

    public ConfigLoader() {
        this.plugin = Jecon.getInstance();
        this.messageFile = new File(plugin.getDataFolder(), MESSAGE_FILE);
    }

    public void reloadConfig() {
        plugin.saveDefaultConfig();
        if (!messageFile.exists()) {
            plugin.saveResource(MESSAGE_FILE, false);
        }

        if (mainConfig != null) {
            plugin.reloadConfig();
        }
        messageYaml = YamlConfiguration.loadConfiguration(messageFile);

        FileConfiguration mainYaml = plugin.getConfig();
        if (MainMigration.migration(mainYaml)) {
            plugin.saveConfig();
        }
        if (MessageMigration.migration(messageYaml)) {
            try {
                messageYaml.save(messageFile);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        mainConfig = new MainConfig(mainYaml);
        messageConfig = new MessageConfig(messageYaml);
    }

    public MainConfig getMainConfig() {
        return mainConfig;
    }

    public MessageConfig getMessageConfig() {
        return messageConfig;
    }
}
