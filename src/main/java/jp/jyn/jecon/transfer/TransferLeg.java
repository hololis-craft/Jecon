package jp.jyn.jecon.transfer;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 単一の振替 leg（from → to、金額）。金額は非負を推奨。
 */
public record TransferLeg(UUID from, UUID to, BigDecimal amount) {}
