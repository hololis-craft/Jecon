package jp.jyn.jecon.account;

import java.time.Instant;
import java.util.UUID;

/**
 * {@link Account} の record 実装。
 *
 * <p>{@code namespace} は非 Player 口座で alias の {@code ':'} 前部分を派生保持する（索引用途）。
 * Player 口座では {@code null}。
 */
public record AccountRecord(
    UUID uuid,
    String alias,
    boolean isPlayer,
    String namespace,
    Instant createdAt
) implements Account {}
