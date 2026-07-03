package jp.jyn.jecon.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.configuration.PluginMeta;
import jp.jyn.jecon.Jecon;
import jp.jyn.jecon.config.ConfigLoader;
import jp.jyn.jecon.config.MessageConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

@SuppressWarnings("UnstableApiUsage")
public class Version {
    private final Jecon plugin;
    private final ConfigLoader config;

    public Version(Jecon plugin, ConfigLoader config) {
        this.plugin = plugin;
        this.config = config;
    }

    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("version")
                .requires(s -> s.getSender().hasPermission("jecon.version"))
                .executes(this::execute);
    }

    private int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        PluginMeta meta = plugin.getPluginMeta();
        sender.sendMessage(MessageConfig.HEADER);
        sender.sendMessage(Component.text(meta.getName() + " - " + meta.getVersion()));
        sender.sendMessage(Component.text(meta.getDescription() != null ? meta.getDescription() : ""));
        sender.sendMessage(Component.text("Developer: " + String.join(",", meta.getAuthors())));
        sender.sendMessage(Component.text("SourceCode: " + (meta.getWebsite() != null ? meta.getWebsite() : "")));
        return Command.SINGLE_SUCCESS;
    }
}
