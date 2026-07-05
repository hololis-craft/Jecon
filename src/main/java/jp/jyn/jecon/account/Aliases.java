package jp.jyn.jecon.account;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 非 Player 口座の alias（{@code <namespace>:<key>}）に関するユーティリティ。
 *
 * <p>ADR-0013 / 02-account-model.md に従い、
 * namespace は {@code [a-z0-9_-]{1,32}}、key は {@code [a-z0-9_-]{1,64}}。
 * 保存時は小文字に正規化する。
 */
public final class Aliases {
    public static final int NAMESPACE_MAX = 32;
    public static final int KEY_MAX = 64;
    public static final int ALIAS_MAX = NAMESPACE_MAX + 1 + KEY_MAX;

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9_-]{1," + NAMESPACE_MAX + "}");
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9_-]{1," + KEY_MAX + "}");

    private Aliases() {}

    /**
     * 非 Player alias を検証しつつ小文字に正規化する。
     *
     * @throws IllegalArgumentException 形式違反
     */
    public static String normalizeNonPlayer(String alias) {
        if (alias == null) {
            throw new IllegalArgumentException("alias is null");
        }
        String lower = alias.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("non-player alias must contain ':' (got: " + alias + ")");
        }
        String namespace = lower.substring(0, colon);
        String key = lower.substring(colon + 1);
        if (key.indexOf(':') >= 0) {
            throw new IllegalArgumentException("alias must contain exactly one ':' (got: " + alias + ")");
        }
        if (!NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException("invalid namespace: '" + namespace + "' (must match [a-z0-9_-]{1,32})");
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("invalid key: '" + key + "' (must match [a-z0-9_-]{1,64})");
        }
        return lower;
    }

    /**
     * 非 Player alias から type-3 name-based UUID を導出する。
     */
    public static UUID uuidFromAlias(String alias) {
        String normalized = normalizeNonPlayer(alias);
        return UUID.nameUUIDFromBytes(normalized.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * alias から namespace 部分を取り出す。非 Player 用。
     */
    public static String namespaceOf(String nonPlayerAlias) {
        String normalized = normalizeNonPlayer(nonPlayerAlias);
        return normalized.substring(0, normalized.indexOf(':'));
    }

    /**
     * 非 Player alias かどうか（{@code ':'} を含むか）を軽く判定する。
     * 形式チェックまではしないので、Player 名との弁別用にのみ使う。
     */
    public static boolean looksLikeNonPlayer(String alias) {
        return alias != null && alias.indexOf(':') >= 0;
    }
}
