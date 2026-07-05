package jp.jyn.jecon.transfer;

import java.util.Map;

/**
 * {@code TransferContext.metadata} を JSON 文字列にエンコードする最小実装。
 *
 * <p>Metadata は自由文字列キー・値なので、外部 JSON ライブラリを引き込まずに手書きの
 * エスケープで足りる。SQLite の TEXT 列と MySQL の JSON 列の双方に投入できる。
 */
public final class MetadataJson {
    /** 1 レコードあたり 4KB 制限（04-context-and-log.md）。 */
    public static final int MAX_LENGTH = 4096;

    private MetadataJson() {}

    public static String encode(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            appendString(sb, entry.getKey());
            sb.append(':');
            appendString(sb, entry.getValue());
        }
        sb.append('}');
        if (sb.length() > MAX_LENGTH) {
            // 超過分は打ち切り、末尾を "}" で閉じるが不完全 JSON にはならないよう、
            // 4KB 超は空マップに fallback して呼び出し側に警告を任せる。
            return "{}";
        }
        return sb.toString();
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
