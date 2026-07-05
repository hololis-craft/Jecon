package jp.jyn.jecon.transfer;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 実際に DB に書き込まれた leg。primary leg なら {@code legLabel = "primary"}、
 * Modifier が生やした追加 leg なら Modifier が付けたラベル。
 */
public record AppliedLeg(UUID from, UUID to, BigDecimal amount, String legLabel) {}
