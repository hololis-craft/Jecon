package jp.jyn.jecon.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import jp.jyn.jecon.Jecon;
import jp.jyn.jecon.config.ConfigLoader;
import jp.jyn.jecon.config.MessageConfig;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

@SuppressWarnings("UnstableApiUsage")
public class Reload {
    private final Jecon plugin;
    private final ConfigLoader config;

    public Reload(Jecon plugin, ConfigLoader config) {
        this.plugin = plugin;
        this.config = config;
    }

    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("reload")
                .requires(s -> s.getSender().hasPermission("jecon.reload"))
                .executes(this::execute);
    }

    private int execute(CommandContext<CommandSourceStack> ctx) {
        plugin.getServer().getPluginManager().callEvent(new PluginDisableEvent(plugin));
        plugin.onDisable();
        plugin.onEnable();
        plugin.getServer().getPluginManager().callEvent(new PluginEnableEvent(plugin));

        CommandSender sender = ctx.getSource().getSender();
        MessageConfig message = config.getMessageConfig();
        sender.sendMessage(message.reloaded.toString());
        if (sender instanceof Player) {
            Bukkit.getConsoleSender().sendMessage(message.reloaded.toString());
        }
        return Command.SINGLE_SUCCESS;
    }
}
