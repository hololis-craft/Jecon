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
import jp.jyn.jbukkitlib.config.parser.template.variable.TemplateVariable;
import jp.jyn.jecon.Jecon;
import jp.jyn.jecon.config.ConfigLoader;
import jp.jyn.jecon.config.MessageConfig;
import jp.jyn.jecon.repository.BalanceRepository;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.List;

@SuppressWarnings("UnstableApiUsage")
public class Pay {
    private final Jecon plugin;
    private final ConfigLoader config;

    public Pay(Jecon plugin, ConfigLoader config) {
        this.plugin = plugin;
        this.config = config;
    }

    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("pay")
                .requires(s -> s.getSender().hasPermission("jecon.pay"))
                .then(Commands.argument("player", ArgumentTypes.player())
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(this::execute)));
    }

    private int execute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Entity executor = ctx.getSource().getExecutor();
        if (!(executor instanceof Player player)) {
            ctx.getSource().getSender().sendPlainMessage(MessageConfig.PLAYER_ONLY);
            return Command.SINGLE_SUCCESS;
        }

        PlayerSelectorArgumentResolver selector =
                ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
        List<Player> targets = selector.resolve(ctx.getSource());
        if (targets.isEmpty()) {
            return Command.SINGLE_SUCCESS;
        }
        Player target = targets.getFirst();

        // self check
        if (player.getUniqueId().equals(target.getUniqueId())) {
            player.sendMessage(config.getMessageConfig().invalidArgument.toString("value", target.getName()));
            return Command.SINGLE_SUCCESS;
        }

        BigDecimal amount = BigDecimal.valueOf(DoubleArgumentType.getDouble(ctx, "amount"));
        MessageConfig message = config.getMessageConfig();
        BalanceRepository repository = plugin.getRepository();

        if (!repository.has(player.getUniqueId(), amount)) {
            player.sendMessage(message.notEnough.toString());
            return Command.SINGLE_SUCCESS;
        }
        if (!repository.hasAccount(target.getUniqueId())) {
            player.sendMessage(message.accountNotFound.toString("name", target.getName()));
            return Command.SINGLE_SUCCESS;
        }

        repository.withdraw(player.getUniqueId(), amount);
        repository.deposit(target.getUniqueId(), amount);

        TemplateVariable variable = StringVariable.init().put("amount", repository.format(amount));
        player.sendMessage(message.paySuccess.toString(variable.put("name", target.getName())));
        target.sendMessage(message.payReceive.toString(variable.put("name", player.getName())));
        return Command.SINGLE_SUCCESS;
    }
}
