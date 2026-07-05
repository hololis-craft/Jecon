package jp.jyn.jecon;

import jp.jyn.jecon.account.AccountService;
import jp.jyn.jecon.account.Aliases;
import jp.jyn.jecon.config.MainConfig;
import jp.jyn.jecon.db.Database;
import jp.jyn.jecon.repository.AbstractRepository;
import jp.jyn.jecon.repository.BalanceRepository;
import jp.jyn.jecon.transfer.TransferContext;
import jp.jyn.jecon.transfer.TransferResult;
import jp.jyn.jecon.transfer.TransferService;
import jp.jyn.jecon.vault.VaultCallerGuess;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 旧 Vault ({@code net.milkbowl.vault.economy.Economy}) を実装するブリッジ。
 *
 * <p>{@code depositPlayer} / {@code withdrawPlayer} を {@link TransferService} に翻訳し、
 * ctx.source={@code "vault_bridge"}、metadata[{@code "vault_caller"}] に呼び出し元プラグイン名を載せる
 * （ADR-0006、08-vault-bridge.md）。対向は {@code system:vault_bridge} 口座。
 */
class VaultEconomy implements Economy {
    /** Vault 経由呼び出しの対向口座。 */
    public static final String VAULT_BRIDGE_ALIAS = "system:vault_bridge";
    public static final UUID VAULT_BRIDGE_UUID = Aliases.uuidFromAlias(VAULT_BRIDGE_ALIAS);

    private BigDecimal defaultBalance;

    private Database db;
    private MainConfig config;
    private BalanceRepository repository;
    private TransferService transferService;
    private AccountService accountService;

    VaultEconomy(MainConfig config, Database db, BalanceRepository repository,
                 TransferService transferService, AccountService accountService) {
        this.init(config, db, repository, transferService, accountService);
    }

    void init(MainConfig config, Database db, BalanceRepository repository,
              TransferService transferService, AccountService accountService) {
        this.db = db;
        this.config = config;
        this.repository = repository;
        this.transferService = transferService;
        this.accountService = accountService;

        this.defaultBalance = config.defaultBalance;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "Jecon";
    }

    @Override
    public int fractionalDigits() {
        return AbstractRepository.FRACTIONAL_DIGITS;
    }

    @Override
    public String format(double v) {
        return repository.format(v);
    }

    @Override
    public String currencyNamePlural() {
        return config.format.pluralMajor;
    }

    @Override
    public String currencyNameSingular() {
        return config.format.singularMajor;
    }

    @Override
    public boolean hasAccount(String s) {
        return accountService.resolveAlias(s).map(repository::hasAccount).orElse(false);
    }

    @Override
    public boolean hasAccount(OfflinePlayer offlinePlayer) {
        return repository.hasAccount(offlinePlayer.getUniqueId());
    }

    @Override
    public boolean hasAccount(String s, String s1) {
        return hasAccount(s);
    }

    @Override
    public boolean hasAccount(OfflinePlayer offlinePlayer, String s) {
        return hasAccount(offlinePlayer);
    }

    @Override
    public double getBalance(String s) {
        return accountService.resolveAlias(s)
            .map(uuid -> repository.getDouble(uuid).orElse(0D))
            .orElse(0D);
    }

    @Override
    public double getBalance(OfflinePlayer offlinePlayer) {
        return repository.getDouble(offlinePlayer.getUniqueId()).orElse(0);
    }

    @Override
    public double getBalance(String s, String s1) {
        return getBalance(s);
    }

    @Override
    public double getBalance(OfflinePlayer offlinePlayer, String s) {
        return getBalance(offlinePlayer);
    }

    @Override
    public boolean has(String s, double v) {
        return accountService.resolveAlias(s).map(uuid -> repository.has(uuid, v)).orElse(false);
    }

    @Override
    public boolean has(OfflinePlayer offlinePlayer, double v) {
        return repository.has(offlinePlayer.getUniqueId(), v);
    }

    @Override
    public boolean has(String s, String s1, double v) {
        return has(s, v);
    }

    @Override
    public boolean has(OfflinePlayer offlinePlayer, String s, double v) {
        return has(offlinePlayer, v);
    }

    @Override
    public boolean createPlayerAccount(String s) {
        return accountService.resolveAlias(s).map(uuid -> repository.createAccount(uuid, defaultBalance)).orElse(false);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer offlinePlayer) {
        UUID uuid = offlinePlayer.getUniqueId();
        String name = offlinePlayer.getName();
        // account 行を確保。名前が判明していれば alias として反映する。
        db.getOrCreatePlayerId(uuid);
        if (name != null && !name.isEmpty()) {
            db.renameAccount(uuid, name);
        }
        return repository.createAccount(uuid, defaultBalance);
    }

    @Override
    public boolean createPlayerAccount(String s, String s1) {
        return createPlayerAccount(s);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer offlinePlayer, String s) {
        return createPlayerAccount(offlinePlayer);
    }

    private EconomyResponse withdrawPlayer(UUID uuid, double value) {
        if (value < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot withdraw negative funds");
        }
        return runVaultTransfer(uuid, VAULT_BRIDGE_UUID, value, "withdrawPlayer");
    }

    @Override
    public EconomyResponse withdrawPlayer(String s, double v) {
        return accountService.resolveAlias(s)
            .map(uuid -> withdrawPlayer(uuid, v))
            .orElseGet(() -> new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "User does not exist"));
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer offlinePlayer, double v) {
        return withdrawPlayer(offlinePlayer.getUniqueId(), v);
    }

    @Override
    public EconomyResponse withdrawPlayer(String s, String s1, double v) {
        return withdrawPlayer(s, v);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer offlinePlayer, String s, double v) {
        return withdrawPlayer(offlinePlayer, v);
    }

    private EconomyResponse depositPlayer(UUID uuid, double value) {
        if (value < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot deposit negative funds");
        }
        return runVaultTransfer(VAULT_BRIDGE_UUID, uuid, value, "depositPlayer");
    }

    @Override
    public EconomyResponse depositPlayer(String s, double v) {
        return accountService.resolveAlias(s)
            .map(uuid -> depositPlayer(uuid, v))
            .orElseGet(() -> new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "User does not exist"));
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer offlinePlayer, double v) {
        return depositPlayer(offlinePlayer.getUniqueId(), v);
    }

    @Override
    public EconomyResponse depositPlayer(String s, String s1, double v) {
        return depositPlayer(s, v);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer offlinePlayer, String s, double v) {
        return depositPlayer(offlinePlayer, v);
    }

    private EconomyResponse runVaultTransfer(UUID from, UUID to, double amount, String vaultMethod) {
        // 対向が player 側の場合、対象口座が存在しなければエラー。
        UUID player = from.equals(VAULT_BRIDGE_UUID) ? to : from;
        if (!repository.hasAccount(player)) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Account does not exist");
        }

        BigDecimal decimal = BigDecimal.valueOf(amount).setScale(AbstractRepository.FRACTIONAL_DIGITS,
            java.math.RoundingMode.HALF_UP);

        String caller = VaultCallerGuess.guess();
        TransferContext ctx = TransferContext.builder()
            .source("vault_bridge")
            .metadata("vault_caller", caller)
            .metadata("vault_method", vaultMethod)
            .withOverdraft()  // system:vault_bridge は常時 overdraft
            .build();
        TransferResult result = transferService.transfer(from, to, decimal, ctx);
        return mapResult(result, player);
    }

    private EconomyResponse mapResult(TransferResult result, UUID player) {
        double newBalance = repository.getDouble(player).orElse(0D);
        return switch (result) {
            case TransferResult.Success s -> {
                BigDecimal amount = s.legs().isEmpty() ? BigDecimal.ZERO : s.legs().get(0).amount();
                yield new EconomyResponse(amount.doubleValue(), newBalance, EconomyResponse.ResponseType.SUCCESS, "OK");
            }
            case TransferResult.InsufficientFunds ignored ->
                new EconomyResponse(0, newBalance, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
            case TransferResult.Vetoed v ->
                new EconomyResponse(0, newBalance, EconomyResponse.ResponseType.FAILURE, v.reason());
            case TransferResult.AccountMissing missing ->
                new EconomyResponse(0, newBalance, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Account missing: " + missing.which());
            case TransferResult.InvalidAmount invalid ->
                new EconomyResponse(0, newBalance, EconomyResponse.ResponseType.FAILURE, invalid.reason());
        };
    }

    // region bank
    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public EconomyResponse createBank(String s, String s1) {
        return notImplementedBank();
    }

    @Override
    public EconomyResponse createBank(String s, OfflinePlayer offlinePlayer) {
        return notImplementedBank();
    }

    @Override
    public EconomyResponse deleteBank(String s) {
        return notImplementedBank();
    }

    @Override
    public EconomyResponse bankBalance(String s) {
        return notImplementedBank();
    }

    @Override
    public EconomyResponse bankHas(String s, double v) {
        return notImplementedBank();
    }

    @Override
    public EconomyResponse bankWithdraw(String s, double v) {
        return notImplementedBank();
    }

    @Override
    public EconomyResponse bankDeposit(String s, double v) {
        return notImplementedBank();
    }

    @Override
    public EconomyResponse isBankOwner(String s, String s1) {
        return notImplementedBank();
    }

    @Override
    public EconomyResponse isBankOwner(String s, OfflinePlayer offlinePlayer) {
        return notImplementedBank();
    }

    @Override
    public EconomyResponse isBankMember(String s, String s1) {
        return notImplementedBank();
    }

    @Override
    public EconomyResponse isBankMember(String s, OfflinePlayer offlinePlayer) {
        return notImplementedBank();
    }

    @Override
    public List<String> getBanks() {
        return Collections.emptyList();
    }

    private static EconomyResponse notImplementedBank() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Jecon does not support bank.");
    }
    // endregion
}
