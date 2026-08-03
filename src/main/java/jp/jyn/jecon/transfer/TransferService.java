package jp.jyn.jecon.transfer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 口座間の原子的な振替 API。単一 leg の {@link #transfer} と複数 leg の {@link #transferBatch}。
 *
 * <p>ADR-0004 / 03-transfer-api.md 相当。両呼び出しとも Modifier pipeline を経由する。
 */
public interface TransferService {
    TransferResult transfer(UUID from, UUID to, BigDecimal amount, TransferContext ctx);

    TransferResult transferBatch(List<TransferLeg> legs, TransferContext ctx);

    /**
     * 口座の残高を指定値に設定する。差分は system 口座との振替として監査ログに残る。
     *
     * <p>「現在の残高を読む → 差分を求める」という手順が必要なため、内部では楽観的並行制御を
     * 使う。読んだ残高がトランザクション内で変わっていれば Modifier pipeline を含めて
     * やり直す。上限まで競合した場合は {@link TransferResult.Conflict} を返す。
     *
     * @param account 対象口座
     * @param target  設定後の残高（負値も可。overdraft 扱いになる）
     */
    TransferResult setBalance(UUID account, BigDecimal target, TransferContext ctx);
}
