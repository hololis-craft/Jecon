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
import jp.jyn.jbukkitlib.config.parser.template.variable.StringVariable;
import jp.jyn.jecon.Jecon;
import jp.jyn.jecon.config.ConfigLoader;
import jp.jyn.jecon.config.MessageConfig;
import jp.jyn.jecon.repository.BalanceRepository;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.List;

@SuppressWarnings("UnstableApiUsage")
public class Create {
    private final Jecon plugin;
    private final ConfigLoader config;

    public Create(Jecon plugin, ConfigLoader config) {
        this.plugin = plugin;
        this.config = config;
    }

    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("create")
                .requires(s -> s.getSender().hasPermission("jecon.create"))
                .then(Commands.argument("player", ArgumentTypes.player())
                        .executes(this::executeDefault)
                        .then(Commands.argument("balance", DoubleArgumentType.doubleArg(0))
                                .executes(this::executeWithBalance)));
    }

    private int executeDefault(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return doCreate(ctx, config.getMainConfig().defaultBalance);
    }

    private int executeWithBalance(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return doCreate(ctx, BigDecimal.valueOf(DoubleArgumentType.getDouble(ctx, "balance")));
    }

    private int doCreate(CommandContext<CommandSourceStack> ctx, BigDecimal balance) throws CommandSyntaxException {
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

        if (repository.hasAccount(target.getUniqueId())) {
            sender.sendMessage(message.createAlready.toString("name", target.getName()));
            return Command.SINGLE_SUCCESS;
        }
        repository.createAccount(target.getUniqueId(), balance);
        sender.sendMessage(message.create.toString(StringVariable.init()
                .put("name", target.getName())
                .put("balance", repository.format(balance))));
        return Command.SINGLE_SUCCESS;
    }
}
