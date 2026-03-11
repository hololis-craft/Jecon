package jp.jyn.jecon.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
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

@SuppressWarnings("UnstableApiUsage")
public class Give {
    private final Jecon plugin;
    private final ConfigLoader config;

    public Give(Jecon plugin, ConfigLoader config) {
        this.plugin = plugin;
        this.config = config;
    }

    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("give")
                .requires(s -> s.getSender().hasPermission("jecon.give"))
                .then(Commands.argument("player", ArgumentTypes.player())
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(this::execute)));
    }

    private int execute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver selector =
                ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
        List<Player> targets = selector.resolve(ctx.getSource());
        if (targets.isEmpty()) {
            return Command.SINGLE_SUCCESS;
        }
        Player target = targets.getFirst();
        BigDecimal amount = BigDecimal.valueOf(DoubleArgumentType.getDouble(ctx, "amount"));
        CommandSender sender = ctx.getSource().getSender();
        MessageConfig message = config.getMessageConfig();
        BalanceRepository repository = plugin.getRepository();

        if (!repository.hasAccount(target.getUniqueId())) {
            sender.sendMessage(message.accountNotFound.toComponent("name", target.getName()));
            return Command.SINGLE_SUCCESS;
        }
        repository.deposit(target.getUniqueId(), amount);
        sender.sendMessage(message.give.toComponent(
                Placeholder.unparsed("name", target.getName()),
                Placeholder.unparsed("amount", repository.format(amount))));
        return Command.SINGLE_SUCCESS;
    }
}
