package jp.jyn.jecon.modifier;

import jp.jyn.jecon.account.AccountPermission;
import jp.jyn.jecon.transfer.TransferLeg;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Modifier が pipeline 内から現在の leg 列や口座状態を照会するための read-only API。
 */
public interface TransferProbe {
    List<TransferLeg> legs();

    BigDecimal getBalance(UUID account);

    boolean isOverdraftAllowed();

    boolean isPlayer(UUID account);

    Optional<String> alias(UUID account);

    boolean hasPermission(UUID account, UUID member, AccountPermission perm);
}
