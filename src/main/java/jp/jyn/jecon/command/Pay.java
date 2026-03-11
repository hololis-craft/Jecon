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
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.math.BigDecimal;

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
                .then(CachedPlayerArgument.player(plugin)
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(this::execute)));
    }

    private int execute(CommandContext<CommandSourceStack> ctx) {
        Entity executor = ctx.getSource().getExecutor();
        if (!(executor instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(MessageConfig.PLAYER_ONLY);
            return Command.SINGLE_SUCCESS;
        }

        CachedPlayerArgument.Target target = CachedPlayerArgument.resolve(ctx)
                .orElse(null);
        if (target == null) {
            return CachedPlayerArgument.notFound(ctx, config.getMessageConfig());
        }

        // self check
        if (player.getUniqueId().equals(target.uuid())) {
            player.sendMessage(config.getMessageConfig().invalidArgument.toComponent("value", target.name()));
            return Command.SINGLE_SUCCESS;
        }

        BigDecimal amount = BigDecimal.valueOf(DoubleArgumentType.getDouble(ctx, "amount"));
        MessageConfig message = config.getMessageConfig();
        BalanceRepository repository = plugin.getRepository();

        if (!repository.has(player.getUniqueId(), amount)) {
            player.sendMessage(message.notEnough.toComponent());
            return Command.SINGLE_SUCCESS;
        }
        if (!repository.hasAccount(target.uuid())) {
            player.sendMessage(message.accountNotFound.toComponent("name", target.name()));
            return Command.SINGLE_SUCCESS;
        }

        repository.withdraw(player.getUniqueId(), amount);
        repository.deposit(target.uuid(), amount);

        String formattedAmount = repository.format(amount);
        player.sendMessage(message.paySuccess.toComponent(
                Placeholder.unparsed("amount", formattedAmount),
                Placeholder.unparsed("name", target.name())));
        Player onlineTarget = Bukkit.getPlayerExact(target.name());
        if (onlineTarget != null && onlineTarget.isOnline()) {
            onlineTarget.sendMessage(message.payReceive.toComponent(
                    Placeholder.unparsed("amount", formattedAmount),
                    Placeholder.unparsed("name", player.getName())));
        }
        return Command.SINGLE_SUCCESS;
    }
}
