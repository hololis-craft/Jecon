package jp.jyn.jecon.config.migration;

import jp.jyn.jecon.Jecon;
import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

class MigrationUtils {
    private MigrationUtils() {}

    final static String ERROR_1 = "Unable to automatically migrate the settings.";
    final static String ERROR_2 = "Delete %s and reload it.";
    final static String EXCEPTION = "Unknown version(%s:%d)";

    static void move(ConfigurationSection config, String oldKey, String newKey) {
        Object old = config.get(oldKey);
        config.set(oldKey, null);
        config.set(newKey, old);
    }

    static void copy(String from,String to) {
        Path data = Jecon.getInstance().getDataFolder().toPath();
        try {
            Files.copy(data.resolve(from), data.resolve(to), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
