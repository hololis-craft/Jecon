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
import net.kyori.adventure.text.Component;
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
            sender.sendMessage(Component.text("Convert from SQLite to MySQL"));
            sender.sendMessage(Component.text("Convert to:"));
            sender.sendMessage(Component.text("Host: " + db.getString("mysql.host")));
            sender.sendMessage(Component.text("Name: " + db.getString("mysql.name")));
            sender.sendMessage(Component.text("User: " + db.getString("mysql.username")));
            sender.sendMessage(Component.text("Pass: " + db.getString("mysql.password")));
        } else if (old.url.startsWith("jdbc:mysql:")) {
            sender.sendMessage(Component.text("Convert from MySQL to SQLite"));
            sender.sendMessage(Component.text("Convert to:"));
            sender.sendMessage(Component.text("File: " + db.getString("sqlite.file")));
        } else {
            sender.sendMessage(Component.text("Unsupported Database"));
            return Command.SINGLE_SUCCESS;
        }

        sender.sendMessage(Component.text(""));
        sender.sendMessage(Component.text("All data in the destination database is deleted."));
        sender.sendMessage(Component.text("Please be sure to back up."));
        sender.sendMessage(Component.text(""));
        sender.sendMessage(Component.text("If there is no problem, please execute '/money convert confirm'."));
        sender.sendMessage(Component.text("If you need to change the settings, edit config.yml."));
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
            sender.sendMessage(Component.text("Unsupported Database"));
            return Command.SINGLE_SUCCESS;
        }
        yamlConfig.saveConfig();
        sender.sendMessage(Component.text("Config reloading."));
        config.reloadConfig();

        sender.sendMessage(Component.text("Connect to database."));
        Database db = Database.connect(config.getMainConfig().database);

        sender.sendMessage(Component.text("Saving unsaved data."));
        plugin.getSaveAll().run();

        sender.sendMessage(Component.text("Converting..."));
        db.convert(oldDB);
        sender.sendMessage(Component.text("Converted."));

        sender.sendMessage(Component.text("Reloading..."));
        plugin.onDisable();
        db.close();
        plugin.onEnable();
        sender.sendMessage(Component.text("Successfully completed."));
        return Command.SINGLE_SUCCESS;
    }
}
