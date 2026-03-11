package jp.jyn.jecon.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import jp.jyn.jecon.Jecon;
import jp.jyn.jecon.config.ConfigLoader;
import jp.jyn.jecon.config.MessageConfig;
import jp.jyn.jecon.repository.BalanceRepository;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

import java.math.BigDecimal;
import java.util.Optional;

@SuppressWarnings("UnstableApiUsage")
public class Remove {
    private final Jecon plugin;
    private final ConfigLoader config;

    public Remove(Jecon plugin, ConfigLoader config) {
        this.plugin = plugin;
        this.config = config;
    }

    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("remove")
                .requires(s -> s.getSender().hasPermission("jecon.remove"))
                .then(CachedPlayerArgument.player(plugin)
                        .executes(this::execute));
    }

    private int execute(CommandContext<CommandSourceStack> ctx) {
        CachedPlayerArgument.Target target = CachedPlayerArgument.resolve(ctx)
                .orElse(null);
        if (target == null) {
            return CachedPlayerArgument.notFound(ctx, config.getMessageConfig());
        }
        CommandSender sender = ctx.getSource().getSender();
        MessageConfig message = config.getMessageConfig();
        BalanceRepository repository = plugin.getRepository();

        Optional<BigDecimal> balance = repository.getDecimal(target.uuid());
        if (balance.isEmpty()) {
            sender.sendMessage(message.accountNotFound.toComponent("name", target.name()));
            return Command.SINGLE_SUCCESS;
        }
        repository.removeAccount(target.uuid());
        sender.sendMessage(message.remove.toComponent(
                Placeholder.unparsed("name", target.name()),
                Placeholder.unparsed("balance", repository.format(balance.get()))));
        return Command.SINGLE_SUCCESS;
    }
}
