package jp.jyn.jecon.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import jp.jyn.jecon.Jecon;
import jp.jyn.jecon.account.Account;
import jp.jyn.jecon.account.AccountService;
import jp.jyn.jecon.account.Aliases;
import jp.jyn.jecon.config.ConfigLoader;
import jp.jyn.jecon.transfer.TransferContext;
import jp.jyn.jecon.transfer.TransferResult;
import jp.jyn.jecon.transfer.TransferService;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /jecon account} 系サブコマンド。
 *
 * <ul>
 *   <li>{@code /jecon account create <alias> [initial]}: 非 Player 口座を作る</li>
 *   <li>{@code /jecon account list <namespace>}: 指定 namespace の口座を列挙</li>
 *   <li>{@code /jecon account send <from> <to> <amount>}: alias/UUID 指定で振替</li>
 * </ul>
 */
@SuppressWarnings("UnstableApiUsage")
public class AccountCommand {
    private static final int LIST_PAGE_SIZE = 20;

    private final Jecon plugin;
    @SuppressWarnings("unused")
    private final ConfigLoader config;

    public AccountCommand(Jecon plugin, ConfigLoader config) {
        this.plugin = plugin;
        this.config = config;
    }

    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("account")
            .requires(s -> s.getSender().hasPermission("jecon.account"))
            .then(Commands.literal("create")
                .then(Commands.argument("alias", StringArgumentType.word())
                    .executes(ctx -> executeCreate(ctx, BigDecimal.ZERO))
                    .then(Commands.argument("initial", DoubleArgumentType.doubleArg(0.0))
                        .executes(ctx -> executeCreate(ctx,
                            BigDecimal.valueOf(DoubleArgumentType.getDouble(ctx, "initial"))
                                .setScale(2, RoundingMode.HALF_UP))))))
            .then(Commands.literal("list")
                .then(Commands.argument("namespace", StringArgumentType.word())
                    .executes(this::executeList)))
            .then(Commands.literal("send")
                .then(Commands.argument("from", StringArgumentType.word())
                    .then(Commands.argument("to", StringArgumentType.word())
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0))
                            .executes(this::executeSend)))));
    }

    private int executeCreate(CommandContext<CommandSourceStack> ctx, BigDecimal initial) {
        CommandSender sender = ctx.getSource().getSender();
        String alias = StringArgumentType.getString(ctx, "alias");

        String normalized;
        String namespace;
        try {
            normalized = Aliases.normalizeNonPlayer(alias);
            namespace = Aliases.namespaceOf(normalized);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Invalid alias: " + e.getMessage()));
            return Command.SINGLE_SUCCESS;
        }

        if (!sender.hasPermission("jecon.account.namespace." + namespace + ".create")) {
            sender.sendMessage(Component.text("You do not have permission to create '" + namespace + "' accounts."));
            return Command.SINGLE_SUCCESS;
        }

        AccountService accountService = plugin.getAccountService();
        UUID uuid = Aliases.uuidFromAlias(normalized);
        if (accountService.exists(uuid)) {
            sender.sendMessage(Component.text("Account already exists: " + normalized));
            return Command.SINGLE_SUCCESS;
        }

        try {
            accountService.createAccount(uuid, normalized, false);
        } catch (RuntimeException e) {
            sender.sendMessage(Component.text("Failed to create account: " + e.getMessage()));
            return Command.SINGLE_SUCCESS;
        }

        // 初期残高
        if (initial.signum() > 0) {
            plugin.getRepository().createAccount(uuid, initial);
        } else {
            plugin.getRepository().createAccount(uuid, BigDecimal.ZERO);
        }

        sender.sendMessage(Component.text("Created account: " + normalized + " (uuid=" + uuid + ")"));
        return Command.SINGLE_SUCCESS;
    }

    private int executeList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String namespace = StringArgumentType.getString(ctx, "namespace").toLowerCase(java.util.Locale.ROOT);

        AccountService accountService = plugin.getAccountService();
        List<Account> accounts = accountService.listByNamespace(namespace, LIST_PAGE_SIZE, 0);
        if (accounts.isEmpty()) {
            sender.sendMessage(Component.text("No accounts in namespace: " + namespace));
            return Command.SINGLE_SUCCESS;
        }
        sender.sendMessage(Component.text("Accounts in '" + namespace + "':"));
        for (Account a : accounts) {
            BigDecimal balance = plugin.getRepository().getDecimal(a.uuid()).orElse(BigDecimal.ZERO);
            sender.sendMessage(Component.text("  " + a.alias() + " = " + balance));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int executeSend(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String fromArg = StringArgumentType.getString(ctx, "from");
        String toArg = StringArgumentType.getString(ctx, "to");
        double amount = DoubleArgumentType.getDouble(ctx, "amount");

        Optional<UUID> from = resolveAccount(fromArg);
        Optional<UUID> to = resolveAccount(toArg);
        if (from.isEmpty()) {
            sender.sendMessage(Component.text("Unknown source: " + fromArg));
            return Command.SINGLE_SUCCESS;
        }
        if (to.isEmpty()) {
            sender.sendMessage(Component.text("Unknown destination: " + toArg));
            return Command.SINGLE_SUCCESS;
        }

        // 送金元 alias に namespace があれば、その namespace の transfer 権限を要求する
        String fromNamespace = namespaceOfAlias(fromArg);
        if (fromNamespace != null
            && !sender.hasPermission("jecon.account.namespace." + fromNamespace + ".transfer")) {
            sender.sendMessage(Component.text("You do not have permission to send from '" + fromNamespace + "' accounts."));
            return Command.SINGLE_SUCCESS;
        }

        BigDecimal decimal = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
        TransferService transferService = plugin.getTransferService();

        UUID actor = sender instanceof Player p ? p.getUniqueId() : null;
        TransferContext.Builder builder = TransferContext.builder()
            .source("admin")
            .metadata("command", "account send")
            .actor(actor);
        if (sender.hasPermission("jecon.transfer.overdraft")) {
            builder.withOverdraft();
        }

        TransferResult result = transferService.transfer(from.get(), to.get(), decimal, builder.build());
        sender.sendMessage(Component.text(describeResult(result, fromArg, toArg, decimal)));
        return Command.SINGLE_SUCCESS;
    }

    private Optional<UUID> resolveAccount(String input) {
        // UUID 形式なら直接
        try {
            UUID uuid = UUID.fromString(input);
            if (plugin.getAccountService().exists(uuid)) {
                return Optional.of(uuid);
            }
        } catch (IllegalArgumentException ignored) {
            // fall through to alias resolution
        }
        return plugin.getAccountService().resolveAlias(input);
    }

    private String namespaceOfAlias(String alias) {
        try {
            return Aliases.namespaceOf(alias);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String describeResult(TransferResult result, String from, String to, BigDecimal amount) {
        return switch (result) {
            case TransferResult.Success s -> "Sent " + amount + " from " + from + " to " + to + " (id=" + s.transferId() + ")";
            case TransferResult.InsufficientFunds f -> "Insufficient funds in " + from + " (available=" + f.available() + ", required=" + f.required() + ")";
            case TransferResult.Vetoed v -> "Vetoed by modifier '" + v.modifierId() + "': " + v.reason();
            case TransferResult.AccountMissing m -> "Account missing: " + m.which();
            case TransferResult.InvalidAmount ia -> "Invalid amount: " + ia.reason();
        };
    }
}
