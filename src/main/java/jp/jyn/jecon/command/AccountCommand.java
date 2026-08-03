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
import jp.jyn.jecon.config.MessageConfig;
import jp.jyn.jecon.repository.BalanceRepository;
import jp.jyn.jecon.transfer.TransferContext;
import jp.jyn.jecon.transfer.TransferResult;
import jp.jyn.jecon.transfer.TransferService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
        MessageConfig.AccountMessage message = config.getMessageConfig().account;
        String alias = StringArgumentType.getString(ctx, "alias");

        String normalized;
        String namespace;
        try {
            normalized = Aliases.normalizeNonPlayer(alias);
            namespace = Aliases.namespaceOf(normalized);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(message.invalidAlias.toComponent("reason", e.getMessage()));
            return Command.SINGLE_SUCCESS;
        }

        if (!sender.hasPermission("jecon.account.namespace." + namespace + ".create")) {
            sender.sendMessage(message.createDenied.toComponent("namespace", namespace));
            return Command.SINGLE_SUCCESS;
        }

        AccountService accountService = plugin.getAccountService();
        UUID uuid = Aliases.uuidFromAlias(normalized);
        if (accountService.exists(uuid)) {
            sender.sendMessage(message.createExists.toComponent("name", normalized));
            return Command.SINGLE_SUCCESS;
        }

        try {
            accountService.createAccount(uuid, normalized, false);
        } catch (RuntimeException e) {
            sender.sendMessage(message.createFailed.toComponent("reason", e.getMessage()));
            return Command.SINGLE_SUCCESS;
        }

        BalanceRepository repository = plugin.getRepository();
        BigDecimal initialBalance = initial.signum() > 0 ? initial : BigDecimal.ZERO;
        repository.createAccount(uuid, initialBalance);

        sender.sendMessage(message.createSuccess.toComponent(
            Placeholder.unparsed("name", normalized),
            Placeholder.unparsed("uuid", uuid.toString()),
            Placeholder.unparsed("balance", repository.format(initialBalance))));
        return Command.SINGLE_SUCCESS;
    }

    private int executeList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageConfig.AccountMessage message = config.getMessageConfig().account;
        String namespace = StringArgumentType.getString(ctx, "namespace").toLowerCase(java.util.Locale.ROOT);

        AccountService accountService = plugin.getAccountService();
        List<Account> accounts = accountService.listByNamespace(namespace, LIST_PAGE_SIZE, 0);
        if (accounts.isEmpty()) {
            sender.sendMessage(message.listEmpty.toComponent("namespace", namespace));
            return Command.SINGLE_SUCCESS;
        }

        BalanceRepository repository = plugin.getRepository();
        sender.sendMessage(message.listHeader.toComponent("namespace", namespace));
        for (Account a : accounts) {
            BigDecimal balance = repository.getDecimal(a.uuid()).orElse(BigDecimal.ZERO);
            sender.sendMessage(message.listEntry.toComponent(
                Placeholder.unparsed("name", a.alias()),
                Placeholder.unparsed("balance", repository.format(balance))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int executeSend(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageConfig.AccountMessage message = config.getMessageConfig().account;
        String fromArg = StringArgumentType.getString(ctx, "from");
        String toArg = StringArgumentType.getString(ctx, "to");
        double amount = DoubleArgumentType.getDouble(ctx, "amount");

        Optional<UUID> from = resolveAccount(fromArg);
        Optional<UUID> to = resolveAccount(toArg);
        if (from.isEmpty()) {
            sender.sendMessage(message.unknownSource.toComponent("name", fromArg));
            return Command.SINGLE_SUCCESS;
        }
        if (to.isEmpty()) {
            sender.sendMessage(message.unknownDestination.toComponent("name", toArg));
            return Command.SINGLE_SUCCESS;
        }

        // 送金元 alias に namespace があれば、その namespace の transfer 権限を要求する
        String fromNamespace = namespaceOfAlias(fromArg);
        if (fromNamespace != null
            && !sender.hasPermission("jecon.account.namespace." + fromNamespace + ".transfer")) {
            sender.sendMessage(message.sendDenied.toComponent("namespace", fromNamespace));
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
        sender.sendMessage(renderResult(message, result, fromArg, toArg, decimal));
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

    private Component renderResult(MessageConfig.AccountMessage message,
                                   TransferResult result,
                                   String from,
                                   String to,
                                   BigDecimal amount) {
        BalanceRepository repository = plugin.getRepository();
        return switch (result) {
            case TransferResult.Success s -> message.sendSuccess.toComponent(
                Placeholder.unparsed("amount", repository.format(amount)),
                Placeholder.unparsed("from", from),
                Placeholder.unparsed("to", to),
                Placeholder.unparsed("id", String.valueOf(s.transferId())));
            case TransferResult.InsufficientFunds f -> message.sendInsufficient.toComponent(
                Placeholder.unparsed("from", from),
                Placeholder.unparsed("available", repository.format(f.available())),
                Placeholder.unparsed("required", repository.format(f.required())));
            case TransferResult.Vetoed v -> message.sendVetoed.toComponent(
                Placeholder.unparsed("modifier", v.modifierId()),
                Placeholder.unparsed("reason", v.reason()));
            case TransferResult.AccountMissing m -> message.sendAccountMissing.toComponent(
                "which", m.which().toString());
            case TransferResult.InvalidAmount ia -> message.sendInvalidAmount.toComponent(
                "reason", ia.reason());
            // Conflict は残高を読んでから差分を適用する setBalance 専用なので、
            // このコマンド (transfer) からは返らない。専用メッセージキーを増やすと
            // 既存インストールの message_*.yml に無い分が "null" になるため使い回す。
            case TransferResult.Conflict ignored -> message.sendInvalidAmount.toComponent(
                "reason", "concurrent modification, please retry");
        };
    }
}
