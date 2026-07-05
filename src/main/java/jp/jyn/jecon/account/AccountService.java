package jp.jyn.jecon.account;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 口座の生成・取得・削除・権限操作を提供する公開 API。
 *
 * <p>API 表面は VaultUnlocked（{@code net.milkbowl.vault2.economy.Economy}）に揃える
 * （ADR-0011、ADR-0013）。口座は常に UUID で指定する。
 */
public interface AccountService {
    /**
     * 口座を作成する。
     *
     * <p>{@code isPlayer=false} の場合、{@code alias} は {@code <namespace>:<key>} 形式でなければならない。
     *
     * @return 作成された口座
     * @throws IllegalArgumentException alias 形式が不正
     * @throws IllegalStateException 既に同じ UUID / alias の口座が存在
     */
    Account createAccount(UUID uuid, String alias, boolean isPlayer);

    /**
     * Shared account を作成する（VaultUnlocked の {@code createSharedAccount} と等価）。
     *
     * <p>{@code owner} は初期メンバとして全 {@link AccountPermission} 付きで登録される。
     *
     * @throws IllegalArgumentException alias 形式が不正
     * @throws IllegalStateException 既に同じ UUID / alias の口座が存在
     */
    Account createSharedAccount(UUID uuid, String alias, UUID owner);

    Optional<Account> get(UUID uuid);

    Optional<UUID> resolveAlias(String alias);

    boolean exists(UUID uuid);

    /**
     * alias を変更する（UUID は不変）。
     *
     * @return 変更に成功したら true。口座不在や alias 重複で false
     * @throws IllegalArgumentException 非 Player 口座で alias 形式が不正
     */
    boolean rename(UUID uuid, String newAlias);

    boolean delete(UUID uuid);

    /**
     * 指定 namespace に属する非 Player 口座を列挙する（運用向け）。
     */
    List<Account> listByNamespace(String namespace, int limit, int offset);

    boolean addMember(UUID account, UUID member, AccountPermission... initialPermissions);

    boolean removeMember(UUID account, UUID member);

    boolean setPermission(UUID account, UUID member, AccountPermission perm, boolean value);

    boolean hasPermission(UUID account, UUID member, AccountPermission perm);

    Set<UUID> members(UUID account);
}
