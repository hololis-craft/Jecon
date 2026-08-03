package jp.jyn.jecon.account;

import jp.jyn.jecon.db.Database;

import java.sql.Connection;
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

        Account account = insertAccountTx(uuid, finalAlias, isPlayer, namespace, null);
        observer.onAccountCreated(account);
        return account;
    }

    @Override
    public Account createSharedAccount(UUID uuid, String alias, UUID owner) {
        String finalAlias = Aliases.normalizeNonPlayer(alias);
        String namespace = Aliases.namespaceOf(finalAlias);

        Account account = insertAccountTx(uuid, finalAlias, false, namespace, owner);
        observer.onAccountCreated(account);
        return account;
    }

    /**
     * {@code account} 行、{@code balance} 行、（shared なら）owner の {@code account_member} 行を
     * 単一トランザクションで作る。
     *
     * <p>分割すると、口座はあるが残高行が無い / owner が付いていない中間状態が他スレッドから
     * 観測される。{@code balance} 行をここで作ることで、明示的に作られた口座は常に
     * 送受金可能な状態になる（{@code Database#setBalanceInTx} は行を自動生成しない）。
     */
    private Account insertAccountTx(UUID uuid, String finalAlias, boolean isPlayer, String namespace, UUID owner) {
        try {
            return db.inTransactionWithRetry(connection -> {
                db.insertAccount(connection, uuid, finalAlias, isPlayer, namespace);
                int accountId = db.resolveId(connection, uuid).orElseThrow(() ->
                    new IllegalStateException("account id missing after insert: " + uuid));
                db.createBalance(connection, accountId, 0L);
                if (owner != null) {
                    db.upsertMember(connection, accountId, owner, ALL_PERMISSIONS_MASK, true);
                }
                return db.getAccountByUuid(connection, uuid).orElseThrow(() ->
                    new IllegalStateException("account not found after insert: " + uuid));
            });
        } catch (RuntimeException e) {
            if (findSqlCause(e) != null) {
                throw new IllegalStateException(
                    "failed to create account (uuid or alias may already exist): " + finalAlias, e);
            }
            throw e;
        }
    }

    private static SQLException findSqlCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
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
        // account 行 → balance 行の順にロックしてから消す。並行する振替は同じ順序で
        // ロックを取るため、削除の途中状態に割り込めない。
        Account removed = db.inTransactionWithRetry(connection -> {
            OptionalInt idOpt = db.resolveId(connection, uuid);
            if (idOpt.isEmpty()) {
                return null;
            }
            int id = idOpt.getAsInt();
            if (!db.lockAccountRow(connection, id)) {
                return null;
            }
            db.selectBalanceForUpdate(connection, id);

            Account before = db.getAccountByUuid(connection, uuid).orElse(null);
            db.deleteAllMembers(connection, id);
            db.removeBalance(connection, id);
            return db.deleteAccountRow(connection, uuid) ? before : null;
        });

        if (removed == null) {
            return false;
        }
        observer.onAccountRemoved(removed);
        return true;
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
        int mask = mask(initialPermissions);
        return withLockedAccount(account, (connection, accountId) ->
            db.upsertMember(connection, accountId, member, mask, false));
    }

    @Override
    public boolean removeMember(UUID account, UUID member) {
        return withLockedAccount(account, (connection, accountId) ->
            db.removeMember(connection, accountId, member));
    }

    @Override
    public boolean setPermission(UUID account, UUID member, AccountPermission perm, boolean value) {
        int bit = 1 << perm.ordinal();
        // read-modify-write。account 行のロック下で行うので、同じ口座への並行更新で
        // ビットが取りこぼされることはない。
        return withLockedAccount(account, (connection, accountId) -> {
            int existing = db.getMemberPermissions(connection, accountId, member);
            int base = existing < 0 ? 0 : existing;
            int updated = value ? (base | bit) : (base & ~bit);
            return db.upsertMember(connection, accountId, member, updated, false);
        });
    }

    /**
     * {@code account} 行のロックを取ってから作業を実行する。
     *
     * <p>{@code account_member} を直接 {@code FOR UPDATE} すると、行が存在しない場合に
     * MySQL が gap lock を取る。gap lock 同士は競合しないので、2 つのトランザクションが
     * 揃って INSERT に進んでデッドロックする。常に存在する {@code account} 行を
     * ロック地点にすればこれを回避できる。
     *
     * @return 口座が存在しなければ false
     */
    private boolean withLockedAccount(UUID account, LockedAccountWork work) {
        return db.inTransactionWithRetry(connection -> {
            OptionalInt idOpt = db.resolveId(connection, account);
            if (idOpt.isEmpty()) {
                return false;
            }
            int accountId = idOpt.getAsInt();
            if (!db.lockAccountRow(connection, accountId)) {
                return false;
            }
            return work.apply(connection, accountId);
        });
    }

    @FunctionalInterface
    private interface LockedAccountWork {
        boolean apply(Connection connection, int accountId) throws SQLException;
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
