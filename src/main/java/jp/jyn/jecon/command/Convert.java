package jp.jyn.jecon.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import jp.jyn.jbukkitlib.config.YamlLoader;
import jp.jyn.jecon.Jecon;
import jp.jyn.jecon.config.ConfigLoader;
import jp.jyn.jecon.config.MainConfig;
import jp.jyn.jecon.db.Database;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

@SuppressWarnings("UnstableApiUsage")
public class Convert {
    private final Jecon plugin;
    private final ConfigLoader config;

    public Convert(Jecon plugin, ConfigLoader config) {
        this.plugin = plugin;
        this.config = config;
    }

    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("convert")
                .requires(s -> s.getSender().hasPermission("jecon.convert"))
                .executes(this::executeConfirm)
                .then(Commands.literal("confirm")
                        .executes(this::executeConvert));
    }

    private int executeConfirm(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        YamlLoader yamlConfig = new YamlLoader(plugin, "config.yml");
        MainConfig.DatabaseConfig old = config.getMainConfig().database;
        ConfigurationSection db = yamlConfig.getConfig().getConfigurationSection("database");

        if (old.url.startsWith("jdbc:sqlite:")) {
            sender.sendMessage("Convert from SQLite to MySQL");
            sender.sendMessage("Convert to:");
            sender.sendMessage("Host: " + db.getString("mysql.host"));
            sender.sendMessage("Name: " + db.getString("mysql.name"));
            sender.sendMessage("User: " + db.getString("mysql.username"));
            sender.sendMessage("Pass: " + db.getString("mysql.password"));
        } else if (old.url.startsWith("jdbc:mysql:")) {
            sender.sendMessage("Convert from MySQL to SQLite");
            sender.sendMessage("Convert to:");
            sender.sendMessage("File: " + db.getString("sqlite.file"));
        } else {
            sender.sendMessage("Unsupported Database");
            return Command.SINGLE_SUCCESS;
        }

        sender.sendMessage("");
        sender.sendMessage("All data in the destination database is deleted.");
        sender.sendMessage("Please be sure to back up.");
        sender.sendMessage("");
        sender.sendMessage("If there is no problem, please execute '/money convert confirm'.");
        sender.sendMessage("If you need to change the settings, edit config.yml.");
        return Command.SINGLE_SUCCESS;
    }

    private int executeConvert(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        YamlLoader yamlConfig = new YamlLoader(plugin, "config.yml");
        MainConfig.DatabaseConfig old = config.getMainConfig().database;
        Database oldDB = plugin.getDb();

        if (old.url.startsWith("jdbc:sqlite:")) {
            yamlConfig.getConfig().set("database.type", "mysql");
        } else if (old.url.startsWith("jdbc:mysql:")) {
            yamlConfig.getConfig().set("database.type", "sqlite");
        } else {
            sender.sendMessage("Unsupported Database");
            return Command.SINGLE_SUCCESS;
        }
        yamlConfig.saveConfig();
        sender.sendMessage("Config reloading.");
        config.reloadConfig();

        sender.sendMessage("Connect to database.");
        Database db = Database.connect(config.getMainConfig().database);

        sender.sendMessage("Saving unsaved data.");
        plugin.getSaveAll().run();

        sender.sendMessage("Converting...");
        db.convert(oldDB);
        sender.sendMessage("Converted.");

        sender.sendMessage("Reloading...");
        plugin.onDisable();
        db.close();
        plugin.onEnable();
        sender.sendMessage("Successfully completed.");
        return Command.SINGLE_SUCCESS;
    }
}
