package jp.jyn.jecon.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * transaction_log の集計クエリを外部プラグイン向けに露出する API
 * （04-context-and-log.md / 06-public-api.md）。
 *
 * <p>クエリは重いので、メインスレッドから叩かない前提。
 */
public interface TransactionQueryService {
    long countBySource(String source, TimeRange range);

    BigDecimal sumBySource(String source, TimeRange range);

    /** {@code account} を出入りした net flow (in − out) を返す。 */
    BigDecimal netFlow(UUID account, TimeRange range);

    /** {@code account} が関与する最新 {@code limit} 件を新しい順に返す。 */
    List<TransactionRow> recent(UUID account, int limit);

    /**
     * {@code source} かつ {@code metadata[key]=value} の合計金額。
     *
     * <p>JSON 列に対する functional index が無い環境 (SQLite) では table scan にフォールバックする。
     */
    BigDecimal sumByMetadata(String source, String metadataKey, String metadataValue, TimeRange range);
}
