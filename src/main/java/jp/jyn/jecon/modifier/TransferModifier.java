package jp.jyn.jecon.modifier;

import jp.jyn.jecon.transfer.TransferContext;

/**
 * 振替の前段に差し込む拡張点。優先度昇順で走る。
 */
public interface TransferModifier {
    String getId();

    int getPriority();

    ModifiedTransfer modify(TransferContext ctx, TransferProbe probe);

    /**
     * {@link #modify} を非メインスレッドから呼んでも安全かどうか。
     *
     * <p>Jecon の振替は任意のスレッドから呼ばれ得る。既定の {@code false} の場合、
     * 非メインスレッドからの振替では pipeline の実行だけをメインスレッドへ回す
     * （その分 1 tick 程度のレイテンシが乗る）。
     *
     * <p>{@code true} を返してよいのは、{@code modify} が {@link TransferProbe} と
     * 自身のスレッドセーフな状態しか触らない場合だけ。<b>Bukkit API
     * ({@code Player}、{@code World}、{@code Inventory}、scoreboard 等) に触るなら
     * {@code false} のままにすること。</b>メインスレッド以外からの Bukkit API 呼び出しは
     * 未定義動作で、サーバを壊し得る。
     */
    default boolean isThreadSafe() {
        return false;
    }
}
