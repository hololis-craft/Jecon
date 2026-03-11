package jp.jyn.jecon.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
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

@SuppressWarnings("UnstableApiUsage")
public class Set {
    private final Jecon plugin;
    private final ConfigLoader config;

    public Set(Jecon plugin, ConfigLoader config) {
        this.plugin = plugin;
        this.config = config;
    }

    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("set")
                .requires(s -> s.getSender().hasPermission("jecon.set"))
                .then(CachedPlayerArgument.player(plugin)
                        .then(Commands.argument("balance", DoubleArgumentType.doubleArg(0))
                                .executes(this::execute)));
    }

    private int execute(CommandContext<CommandSourceStack> ctx) {
        CachedPlayerArgument.Target target = CachedPlayerArgument.resolve(ctx)
                .orElse(null);
        if (target == null) {
            return CachedPlayerArgument.notFound(ctx, config.getMessageConfig());
        }
        BigDecimal balance = BigDecimal.valueOf(DoubleArgumentType.getDouble(ctx, "balance"));
        CommandSender sender = ctx.getSource().getSender();
        MessageConfig message = config.getMessageConfig();
        BalanceRepository repository = plugin.getRepository();

        if (!repository.hasAccount(target.uuid())) {
            sender.sendMessage(message.accountNotFound.toComponent("name", target.name()));
            return Command.SINGLE_SUCCESS;
        }
        repository.set(target.uuid(), balance);
        sender.sendMessage(message.set.toComponent(
                Placeholder.unparsed("name", target.name()),
                Placeholder.unparsed("balance", repository.format(balance))));
        return Command.SINGLE_SUCCESS;
    }
}
