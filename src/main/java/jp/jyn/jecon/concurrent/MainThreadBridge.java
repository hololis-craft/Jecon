package jp.jyn.jecon.concurrent;

import java.util.function.Supplier;

/**
 * サーバのメインスレッドでの実行を仲介する。
 *
 * <p>Jecon は任意のスレッドから振替を受けるが、サードパーティの拡張点
 * ({@code TransferModifier}) は Bukkit API を触る可能性がある。そういう拡張点だけを
 * メインスレッドへ寄せるために使う。
 *
 * <p><b>不変条件: メインスレッドがワーカースレッドの完了を待つ経路を作ってはいけない。</b>
 * 同期 API は呼び出し元のスレッド上でそのまま実行する（executor に投げて待たない）ので、
 * 「ワーカー → メイン」の待ちだけが存在し、循環待ちにならない。
 */
public interface MainThreadBridge {
    boolean isMainThread();

    /**
     * メインスレッドで {@code work} を実行し、その結果を返す。
     * 既にメインスレッドならその場で実行する。
     *
     * @throws MainThreadUnavailableException メインスレッドで実行できなかった
     *                                        （タイムアウト、プラグイン無効化中など）
     */
    <T> T callSync(Supplier<T> work);

    /**
     * 常に「メインスレッドである」として扱い、その場で実行する実装。
     *
     * <p>Bukkit を起動しないテスト用。スレッド跨ぎの検証には使えない。
     */
    MainThreadBridge INLINE = new MainThreadBridge() {
        @Override
        public boolean isMainThread() {
            return true;
        }

        @Override
        public <T> T callSync(Supplier<T> work) {
            return work.get();
        }
    };
}
