package jp.jyn.jecon.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * transaction_log の 1 行を UUID ベースで露出する。
 */
public record TransactionRow(
    long id,
    Instant occurredAt,
    String source,
    UUID from,
    UUID to,
    BigDecimal amount,
    String legLabel,
    Long batchId,
    UUID actor,
    String metadataJson
) {}
