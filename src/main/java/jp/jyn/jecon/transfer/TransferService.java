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
}
