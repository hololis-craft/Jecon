package jp.jyn.jecon.account;

import java.time.Instant;
import java.util.UUID;

/**
 * 口座を表す value object。
 *
 * <p>すべての口座は UUID を主キーに持ち、alias を副次表現として持つ（ADR-0013）。
 * Player 口座なら {@link #alias()} は Minecraft 名、非 Player 口座なら {@code <namespace>:<key>} 形式。
 */
public interface Account {
    UUID uuid();

    String alias();

    boolean isPlayer();

    Instant createdAt();
}
