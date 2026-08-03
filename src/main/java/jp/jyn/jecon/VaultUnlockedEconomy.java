package jp.jyn.jecon;

import jp.jyn.jecon.account.Account;
import jp.jyn.jecon.account.AccountService;
import jp.jyn.jecon.account.Aliases;
import jp.jyn.jecon.config.MainConfig;
import jp.jyn.jecon.db.Database;
import jp.jyn.jecon.repository.AbstractRepository;
import jp.jyn.jecon.repository.BalanceRepository;
import jp.jyn.jecon.transfer.TransferContext;
import jp.jyn.jecon.transfer.TransferResult;
import jp.jyn.jecon.transfer.TransferService;
import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * VaultUnlocked ({@code net.milkbowl.vault2.economy.Economy}) の実装ブリッジ。
 *
 * <p>両端が UUID で明示される {@code transfer} は system 口座を挟まずそのまま
 * {@link TransferService#transfer} に翻訳する。片側 API ({@code deposit}/{@code withdraw})
 * は {@code system:vault_unlocked_bridge} を対向にする。
 * ADR-0011 / 08-vault-bridge.md。
 */
public class VaultUnlockedEconomy implements Economy {
    public static final String VAULT_UNLOCKED_BRIDGE_ALIAS = "system:vault_unlocked_bridge";
    public static final UUID VAULT_UNLOCKED_BRIDGE_UUID = Aliases.uuidFromAlias(VAULT_UNLOCKED_BRIDGE_ALIAS);

    private static final String DEFAULT_CURRENCY = "default";

    private final MainConfig config;
    private final Database db;
    private final BalanceRepository repository;
    private final TransferService transferService;
    private final AccountService accountService;

    public VaultUnlockedEconomy(MainConfig config, Database db, BalanceRepository repository,
                                TransferService transferService, AccountService accountService) {
        this.config = config;
        this.db = db;
        this.repository = repository;
        this.transferService = transferService;
        this.accountService = accountService;
    }

    // ─── Plugin info ─────────────────────────────────────────────────

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "Jecon";
    }

    @Override
    public boolean hasSharedAccountSupport() {
        return true;
    }

    @Override
    public boolean hasMultiCurrencySupport() {
        return false;
    }

    // ─── Currency ────────────────────────────────────────────────────

    @Override
    public int fractionalDigits(String pluginName) {
        return AbstractRepository.FRACTIONAL_DIGITS;
    }

    @Override
    public String format(BigDecimal amount) {
        return repository.format(amount);
    }

    @Override
    public String format(String pluginName, BigDecimal amount) {
        return repository.format(amount);
    }

    @Override
    public String format(BigDecimal amount, String currency) {
        return repository.format(amount);
    }

    @Override
    public String format(String pluginName, BigDecimal amount, String currency) {
        return repository.format(amount);
    }

    @Override
    public boolean hasCurrency(String currency) {
        return DEFAULT_CURRENCY.equals(currency);
    }

    @Override
    public String getDefaultCurrency(String pluginName) {
        return DEFAULT_CURRENCY;
    }

    @Override
    public String defaultCurrencyNamePlural(String pluginName) {
        return config.format.pluralMajor;
    }

    @Override
    public String defaultCurrencyNameSingular(String pluginName) {
        return config.format.singularMajor;
    }

    @Override
    public Collection<String> currencies() {
        return List.of(DEFAULT_CURRENCY);
    }

    // ─── Account lifecycle ───────────────────────────────────────────

    @Override
    public boolean createAccount(UUID uuid, String name) {
        return createAccount(uuid, name, false);
    }

    @Override
    public boolean createAccount(UUID uuid, String name, boolean player) {
        if (accountService.exists(uuid)) {
            return false;
        }
        try {
            // AccountService が account 行と balance 行を同一トランザクションで作るので、
            // ここで repository.createAccount を重ねて呼ぶ必要はない。
            accountService.createAccount(uuid, name, player);
        } catch (RuntimeException e) {
            return false;
        }
        return true;
    }

    @Override
    public boolean createAccount(UUID uuid, String name, String worldName) {
        return createAccount(uuid, name, false);
    }

    @Override
    public boolean createAccount(UUID uuid, String name, String worldName, boolean player) {
        return createAccount(uuid, name, player);
    }

    @Override
    public Map<UUID, String> getUUIDNameMap() {
        // 実運用向けの hot path 想定ではない。空を返しつつ、必要なら別 API を利用させる。
        return new HashMap<>();
    }

    @Override
    public Optional<String> getAccountName(UUID uuid) {
        return db.getAlias(uuid);
    }

    @Override
    public boolean hasAccount(UUID uuid) {
        return accountService.exists(uuid);
    }

    @Override
    public boolean hasAccount(UUID uuid, String worldName) {
        return hasAccount(uuid);
    }

    @Override
    public boolean renameAccount(UUID uuid, String name) {
        return accountService.rename(uuid, name);
    }

    @Override
    public boolean renameAccount(String pluginName, UUID uuid, String name) {
        return accountService.rename(uuid, name);
    }

    @Override
    public boolean deleteAccount(String pluginName, UUID uuid) {
        return accountService.delete(uuid);
    }

    // ─── Currency checks per account ─────────────────────────────────

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID uuid, String currency) {
        return DEFAULT_CURRENCY.equals(currency);
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID uuid, String currency, String world) {
        return DEFAULT_CURRENCY.equals(currency);
    }

    // ─── Balance queries ─────────────────────────────────────────────

    @Override
    public BigDecimal getBalance(String pluginName, UUID uuid) {
        return repository.getDecimal(uuid).orElse(BigDecimal.ZERO);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID uuid, String worldName) {
        return getBalance(pluginName, uuid);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID uuid, String worldName, String currency) {
        return getBalance(pluginName, uuid);
    }

    @Override
    public boolean has(String pluginName, UUID uuid, BigDecimal amount) {
        return repository.has(uuid, amount);
    }

    @Override
    public boolean has(String pluginName, UUID uuid, String worldName, BigDecimal amount) {
        return repository.has(uuid, amount);
    }

    @Override
    public boolean has(String pluginName, UUID uuid, String worldName, String currency, BigDecimal amount) {
        return repository.has(uuid, amount);
    }

    // ─── Balance mutation via TransferService ────────────────────────

    @Override
    public EconomyResponse withdraw(String pluginName, UUID uuid, BigDecimal amount) {
        return runBridgedTransfer(pluginName, uuid, VAULT_UNLOCKED_BRIDGE_UUID, amount, "withdraw");
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID uuid, String worldName, BigDecimal amount) {
        return withdraw(pluginName, uuid, amount);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID uuid, String worldName, String currency, BigDecimal amount) {
        if (!DEFAULT_CURRENCY.equals(currency)) return notImplementedCurrency();
        return withdraw(pluginName, uuid, amount);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID uuid, BigDecimal amount) {
        return runBridgedTransfer(pluginName, VAULT_UNLOCKED_BRIDGE_UUID, uuid, amount, "deposit");
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID uuid, String worldName, BigDecimal amount) {
        return deposit(pluginName, uuid, amount);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID uuid, String worldName, String currency, BigDecimal amount) {
        if (!DEFAULT_CURRENCY.equals(currency)) return notImplementedCurrency();
        return deposit(pluginName, uuid, amount);
    }

    private EconomyResponse runBridgedTransfer(String pluginName, UUID from, UUID to, BigDecimal amount, String vaultMethod) {
        if (amount == null || amount.signum() < 0) {
            return new EconomyResponse(BigDecimal.ZERO, BigDecimal.ZERO,
                EconomyResponse.ResponseType.FAILURE, "Amount must be non-negative");
        }
        BigDecimal scaled = amount.setScale(AbstractRepository.FRACTIONAL_DIGITS, RoundingMode.HALF_UP);
        TransferContext ctx = TransferContext.builder()
            .source("vault_unlocked")
            .metadata("plugin_name", nullSafe(pluginName))
            .metadata("vault_caller", nullSafe(pluginName))
            .metadata("vault_method", vaultMethod)
            .withOverdraft()
            .build();
        TransferResult result = transferService.transfer(from, to, scaled, ctx);
        UUID target = from.equals(VAULT_UNLOCKED_BRIDGE_UUID) ? to : from;
        return mapResult(result, target);
    }

    private EconomyResponse mapResult(TransferResult result, UUID target) {
        BigDecimal newBalance = repository.getDecimal(target).orElse(BigDecimal.ZERO);
        return switch (result) {
            case TransferResult.Success s -> {
                BigDecimal amount = s.legs().isEmpty() ? BigDecimal.ZERO : s.legs().get(0).amount();
                yield new EconomyResponse(amount, newBalance, EconomyResponse.ResponseType.SUCCESS, "OK");
            }
            case TransferResult.InsufficientFunds ignored ->
                new EconomyResponse(BigDecimal.ZERO, newBalance, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
            case TransferResult.Vetoed v ->
                new EconomyResponse(BigDecimal.ZERO, newBalance, EconomyResponse.ResponseType.FAILURE, v.reason());
            case TransferResult.AccountMissing missing ->
                new EconomyResponse(BigDecimal.ZERO, newBalance, EconomyResponse.ResponseType.FAILURE, "Account missing: " + missing.which());
            case TransferResult.InvalidAmount invalid ->
                new EconomyResponse(BigDecimal.ZERO, newBalance, EconomyResponse.ResponseType.FAILURE, invalid.reason());
            case TransferResult.Conflict ignored ->
                new EconomyResponse(BigDecimal.ZERO, newBalance, EconomyResponse.ResponseType.FAILURE,
                    "Concurrent modification, please retry");
        };
    }

    // ─── Shared account API ──────────────────────────────────────────

    @Override
    public boolean createSharedAccount(String pluginName, UUID uuid, String name, UUID owner) {
        if (accountService.exists(uuid)) return false;
        try {
            accountService.createSharedAccount(uuid, name, owner);
        } catch (RuntimeException e) {
            return false;
        }
        return true;
    }

    @Override
    public boolean isAccountOwner(String pluginName, UUID uuid, UUID target) {
        return accountService.hasPermission(uuid, target, jp.jyn.jecon.account.AccountPermission.OWNER);
    }

    @Override
    public boolean setOwner(String pluginName, UUID uuid, UUID newOwner) {
        // 新 owner に OWNER 権限を付与しつつ、他 owner はそのまま残す（VaultUnlocked の仕様に明示なし）。
        return accountService.setPermission(uuid, newOwner, jp.jyn.jecon.account.AccountPermission.OWNER, true);
    }

    @Override
    public boolean isAccountMember(String pluginName, UUID uuid, UUID member) {
        return accountService.members(uuid).contains(member);
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID uuid, UUID member) {
        return accountService.addMember(uuid, member);
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID uuid, UUID member, AccountPermission... initialPerms) {
        jp.jyn.jecon.account.AccountPermission[] mapped = mapPermissions(initialPerms);
        return accountService.addMember(uuid, member, mapped);
    }

    @Override
    public boolean removeAccountMember(String pluginName, UUID uuid, UUID member) {
        return accountService.removeMember(uuid, member);
    }

    @Override
    public boolean hasAccountPermission(String pluginName, UUID uuid, UUID member, AccountPermission perm) {
        return accountService.hasPermission(uuid, member, mapPermission(perm));
    }

    @Override
    public boolean updateAccountPermission(String pluginName, UUID uuid, UUID member, AccountPermission perm, boolean value) {
        return accountService.setPermission(uuid, member, mapPermission(perm), value);
    }

    @Override
    public List<String> accountsOwnedBy(String pluginName, UUID uuid) {
        // 実装コスト vs 需要のトレードオフ: hot path ではないので空を返す。
        return Collections.emptyList();
    }

    @Override
    public List<String> accountsMemberOf(String pluginName, UUID uuid) {
        return Collections.emptyList();
    }

    @Override
    public List<String> accountsAccessTo(String pluginName, UUID uuid, AccountPermission... perms) {
        return Collections.emptyList();
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private static String nullSafe(String s) {
        return s == null ? "unknown" : s;
    }

    private static EconomyResponse notImplementedCurrency() {
        return new EconomyResponse(BigDecimal.ZERO, BigDecimal.ZERO,
            EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Only default currency is supported");
    }

    private static jp.jyn.jecon.account.AccountPermission mapPermission(AccountPermission vault) {
        return jp.jyn.jecon.account.AccountPermission.valueOf(vault.name());
    }

    private static jp.jyn.jecon.account.AccountPermission[] mapPermissions(AccountPermission[] input) {
        if (input == null) return new jp.jyn.jecon.account.AccountPermission[0];
        List<jp.jyn.jecon.account.AccountPermission> list = new ArrayList<>(input.length);
        for (AccountPermission p : input) {
            if (p != null) list.add(mapPermission(p));
        }
        return list.toArray(new jp.jyn.jecon.account.AccountPermission[0]);
    }
}
