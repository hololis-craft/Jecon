package jp.jyn.jecon.command;

import com.mojang.brigadier.Command;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;

@SuppressWarnings("UnstableApiUsage")
public class Show {
    private final Jecon plugin;
    private final ConfigLoader config;

    public Show(Jecon plugin, ConfigLoader config) {
        this.plugin = plugin;
        this.config = config;
    }

    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("show")
                .requires(s -> s.getSender().hasPermission("jecon.show"))
                .executes(this::executeSelf)
                .then(Commands.argument("player", ArgumentTypes.player())
                        .requires(s -> s.getSender().hasPermission("jecon.show.other"))
                        .executes(this::executeOther));
    }

    public int executeSelf(CommandContext<CommandSourceStack> ctx) {
        Entity executor = ctx.getSource().getExecutor();
        CommandSender sender = ctx.getSource().getSender();
        if (!(executor instanceof Player player)) {
            sender.sendPlainMessage(MessageConfig.PLAYER_ONLY);
            return Command.SINGLE_SUCCESS;
        }
        MessageConfig message = config.getMessageConfig();
        BalanceRepository repository = plugin.getRepository();
        sender.sendMessage(repository.format(player.getUniqueId())
                .map(f -> message.show.toString(
                        StringVariable.init().put("name", player.getName()).put("balance", f)))
                .orElseGet(() -> message.accountNotFound.toString("name", player.getName())));
        return Command.SINGLE_SUCCESS;
    }

    private int executeOther(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver selector =
                ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
        List<Player> targets = selector.resolve(ctx.getSource());
        if (targets.isEmpty()) {
            return Command.SINGLE_SUCCESS;
        }
        Player target = targets.getFirst();
        MessageConfig message = config.getMessageConfig();
        BalanceRepository repository = plugin.getRepository();
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(repository.format(target.getUniqueId())
                .map(f -> message.show.toString(
                        StringVariable.init().put("name", target.getName()).put("balance", f)))
                .orElseGet(() -> message.accountNotFound.toString("name", target.getName())));
        return Command.SINGLE_SUCCESS;
    }
}
