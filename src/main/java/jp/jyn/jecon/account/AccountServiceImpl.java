package jp.jyn.jecon.account;

import jp.jyn.jecon.db.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * DB-backed {@link AccountService}。
 *
 * <p>非 Player 口座の alias は必ず {@code <namespace>:<key>} 形式で、
 * {@link Aliases#normalizeNonPlayer(String)} により小文字化・検証してから保存する。
 */
public class AccountServiceImpl implements AccountService {
    /** 権限を int ビットマスクにパックする際のシフト位置は enum ordinal。 */
    private static final int ALL_PERMISSIONS_MASK = mask(AccountPermission.values());

    private final Database db;
    private final AccountLifecycleObserver observer;

    public AccountServiceImpl(Database db, AccountLifecycleObserver observer) {
        this.db = db;
        this.observer = observer;
    }

    @Override
    public Account createAccount(UUID uuid, String alias, boolean isPlayer) {
        String finalAlias;
        String namespace;
        if (isPlayer) {
            if (alias == null || alias.isEmpty()) {
                throw new IllegalArgumentException("player alias must not be empty");
            }
            finalAlias = alias;
            namespace = null;
        } else {
            finalAlias = Aliases.normalizeNonPlayer(alias);
            namespace = Aliases.namespaceOf(finalAlias);
        }

        try {
            db.insertAccount(uuid, finalAlias, isPlayer, namespace);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to create account (uuid or alias may already exist): " + finalAlias, e);
        }

        Account account = db.getAccountByUuid(uuid).orElseThrow(() ->
            new IllegalStateException("account not found after insert: " + uuid));
        observer.onAccountCreated(account);
        return account;
    }

    @Override
    public Account createSharedAccount(UUID uuid, String alias, UUID owner) {
        Account account = createAccount(uuid, alias, false);
        int accountId = db.resolveId(uuid).orElseThrow(() ->
            new IllegalStateException("account id missing after create: " + uuid));
        db.upsertMember(accountId, owner, ALL_PERMISSIONS_MASK, true);
        return account;
    }

    @Override
    public Optional<Account> get(UUID uuid) {
        return db.getAccountByUuid(uuid).map(Account.class::cast);
    }

    @Override
    public Optional<UUID> resolveAlias(String alias) {
        if (alias == null || alias.isEmpty()) {
            return Optional.empty();
        }
        // 非 Player alias は保存時に小文字化しているので、こちら側も小文字化して引く
        String key = alias.indexOf(':') >= 0 ? alias.toLowerCase(Locale.ROOT) : alias;
        return db.resolveAlias(key);
    }

    @Override
    public boolean exists(UUID uuid) {
        return db.resolveId(uuid).isPresent();
    }

    @Override
    public boolean rename(UUID uuid, String newAlias) {
        Optional<Account> existing = get(uuid);
        if (existing.isEmpty()) {
            return false;
        }
        String finalAlias = existing.get().isPlayer()
            ? newAlias
            : Aliases.normalizeNonPlayer(newAlias);
        return db.renameAccount(uuid, finalAlias);
    }

    @Override
    public boolean delete(UUID uuid) {
        OptionalInt idOpt = db.resolveId(uuid);
        if (idOpt.isEmpty()) {
            return false;
        }
        int id = idOpt.getAsInt();
        Optional<Account> before = db.getAccountByUuid(uuid).map(Account.class::cast);

        db.deleteAllMembers(id);
        db.removeBalance(id);
        boolean removed = db.deleteAccountRow(uuid);
        if (removed) {
            before.ifPresent(observer::onAccountRemoved);
        }
        return removed;
    }

    @Override
    public List<Account> listByNamespace(String namespace, int limit, int offset) {
        List<Account> result = new ArrayList<>();
        for (var record : db.listAccountsByNamespace(namespace, limit, offset)) {
            result.add(record);
        }
        return result;
    }

    @Override
    public boolean addMember(UUID account, UUID member, AccountPermission... initialPermissions) {
        OptionalInt idOpt = db.resolveId(account);
        if (idOpt.isEmpty()) {
            return false;
        }
        int mask = mask(initialPermissions);
        return db.upsertMember(idOpt.getAsInt(), member, mask, false);
    }

    @Override
    public boolean removeMember(UUID account, UUID member) {
        OptionalInt idOpt = db.resolveId(account);
        if (idOpt.isEmpty()) {
            return false;
        }
        return db.removeMember(idOpt.getAsInt(), member);
    }

    @Override
    public boolean setPermission(UUID account, UUID member, AccountPermission perm, boolean value) {
        OptionalInt idOpt = db.resolveId(account);
        if (idOpt.isEmpty()) {
            return false;
        }
        int accountId = idOpt.getAsInt();
        int existing = db.getMemberPermissions(accountId, member);
        int bit = 1 << perm.ordinal();
        int base = existing < 0 ? 0 : existing;
        int updated = value ? (base | bit) : (base & ~bit);
        return db.upsertMember(accountId, member, updated, false);
    }

    @Override
    public boolean hasPermission(UUID account, UUID member, AccountPermission perm) {
        OptionalInt idOpt = db.resolveId(account);
        if (idOpt.isEmpty()) {
            return false;
        }
        int mask = db.getMemberPermissions(idOpt.getAsInt(), member);
        if (mask < 0) return false;
        return (mask & (1 << perm.ordinal())) != 0;
    }

    @Override
    public Set<UUID> members(UUID account) {
        OptionalInt idOpt = db.resolveId(account);
        if (idOpt.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(db.listMembers(idOpt.getAsInt()));
    }

    private static int mask(AccountPermission... perms) {
        EnumSet<AccountPermission> set = EnumSet.noneOf(AccountPermission.class);
        if (perms != null) {
            for (AccountPermission p : perms) {
                if (p != null) set.add(p);
            }
        }
        int m = 0;
        for (AccountPermission p : set) {
            m |= (1 << p.ordinal());
        }
        return m;
    }

    /** 口座作成 / 削除のフックポイント。Bukkit event を発火する側で実装する。 */
    public interface AccountLifecycleObserver {
        void onAccountCreated(Account account);

        void onAccountRemoved(Account account);

        AccountLifecycleObserver NOOP = new AccountLifecycleObserver() {
            @Override public void onAccountCreated(Account account) {}
            @Override public void onAccountRemoved(Account account) {}
        };
    }
}
