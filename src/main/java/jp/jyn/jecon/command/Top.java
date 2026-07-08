package jp.jyn.jecon.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import jp.jyn.jecon.Jecon;
import jp.jyn.jecon.config.ConfigLoader;
import jp.jyn.jecon.config.MessageConfig;
import jp.jyn.jecon.db.Database;
import jp.jyn.jecon.repository.BalanceRepository;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

import java.math.BigDecimal;

@SuppressWarnings("UnstableApiUsage")
public class Top {
    private static final int ENTRY_PER_PAGE = 10;
    private final Jecon plugin;
    private final ConfigLoader config;

    public Top(Jecon plugin, ConfigLoader config) {
        this.plugin = plugin;
        this.config = config;
    }

    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("top")
                .requires(s -> s.getSender().hasPermission("jecon.top"))
                .executes(ctx -> execute(ctx, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> execute(ctx, IntegerArgumentType.getInteger(ctx, "page"))));
    }

    private int execute(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSender sender = ctx.getSource().getSender();
        MessageConfig message = config.getMessageConfig();
        BalanceRepository repository = plugin.getRepository();
        Database db = plugin.getDb();

        boolean playerOnly = config.getMainConfig().hideNonPlayerAccounts
                && !sender.hasPermission("jecon.viewnonplayer");

        int offset = (page - 1) * ENTRY_PER_PAGE;
        sender.sendMessage(message.topFirst.toComponent(
                Placeholder.unparsed("page", String.valueOf(page))));

        int i = offset;
        for (Database.TopEntry entry : db.topWithAliases(ENTRY_PER_PAGE, offset, playerOnly)) {
            BigDecimal balance = BigDecimal.valueOf(entry.balance()).scaleByPowerOfTen(-2);
            sender.sendMessage(message.topEntry.toComponent(
                    Placeholder.unparsed("rank", String.valueOf(++i)),
                    Placeholder.unparsed("name", entry.alias() == null ? "Unknown" : entry.alias()),
                    Placeholder.unparsed("balance", repository.format(balance))));
        }
        return Command.SINGLE_SUCCESS;
    }
}
