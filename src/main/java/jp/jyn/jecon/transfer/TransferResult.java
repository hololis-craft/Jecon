package jp.jyn.jecon.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 振替の結果。sealed で網羅的に処理する。
 */
public sealed interface TransferResult {

    record Success(long transferId, Instant occurredAt, List<AppliedLeg> legs) implements TransferResult {}

    record InsufficientFunds(UUID account, BigDecimal available, BigDecimal required) implements TransferResult {}

    record Vetoed(String modifierId, String reason) implements TransferResult {}

    record AccountMissing(UUID which) implements TransferResult {}

    record InvalidAmount(BigDecimal amount, String reason) implements TransferResult {}
}
