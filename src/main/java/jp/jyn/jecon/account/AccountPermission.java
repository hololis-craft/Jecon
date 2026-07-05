package jp.jyn.jecon.account;

/**
 * Shared account の権限。
 *
 * <p>VaultUnlocked の {@code net.milkbowl.vault2.economy.AccountPermission} と等価な意味論を持つ
 * （ADR-0011）。Economy 本体は enum の意味論に介入せず、値を保存・参照するだけ。
 */
public enum AccountPermission {
    DEPOSIT,
    WITHDRAW,
    BALANCE,
    TRANSFER_OWNERSHIP,
    INVITE_MEMBER,
    REMOVE_MEMBER,
    CHANGE_MEMBER_PERMISSION,
    OWNER,
    DELETE
}
