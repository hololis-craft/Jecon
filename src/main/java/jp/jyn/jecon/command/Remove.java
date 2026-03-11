package jp.jyn.jecon.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import jp.jyn.jecon.Jecon;
import jp.jyn.jecon.config.ConfigLoader;
import jp.jyn.jecon.config.MessageConfig;
import jp.jyn.jecon.repository.BalanceRepository;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.List;
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
                .then(Commands.argument("player", ArgumentTypes.player())
                        .executes(this::execute));
    }

    private int execute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver selector =
                ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
        List<Player> targets = selector.resolve(ctx.getSource());
        if (targets.isEmpty()) {
            return Command.SINGLE_SUCCESS;
        }
        Player target = targets.getFirst();
        CommandSender sender = ctx.getSource().getSender();
        MessageConfig message = config.getMessageConfig();
        BalanceRepository repository = plugin.getRepository();

        Optional<BigDecimal> balance = repository.getDecimal(target.getUniqueId());
        if (balance.isEmpty()) {
            sender.sendMessage(message.accountNotFound.toComponent("name", target.getName()));
            return Command.SINGLE_SUCCESS;
        }
        repository.removeAccount(target.getUniqueId());
        sender.sendMessage(message.remove.toComponent(
                Placeholder.unparsed("name", target.getName()),
                Placeholder.unparsed("balance", repository.format(balance.get()))));
        return Command.SINGLE_SUCCESS;
    }
}
