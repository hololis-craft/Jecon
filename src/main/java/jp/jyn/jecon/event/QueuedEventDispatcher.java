package jp.jyn.jecon.event;

import org.bukkit.event.Event;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * event をキューに積み、{@link #drain()} でまとめて発火する {@link EventDispatcher}。
 *
 * <p>Bukkit は同期 event を非メインスレッドから発火すると {@code IllegalStateException} を
 * 投げるので、任意のスレッドから振替を受けるにはスレッドの受け渡しが必要になる。
 * 実運用では毎 tick メインスレッドから {@link #drain()} を呼ぶ。
 *
 * <p><b>メインスレッドからの post も即時発火せずキューに積む。</b>そうしないと、
 * 先に commit された非同期の振替より後の同期の振替が先に届き、event の順序が
 * commit 順とずれる。代償として、メインスレッドの振替でも event は次の tick 以降に
 * なり、呼び出し元の {@code transfer()} が返った後に発火する。
 *
 * <p>発火先を {@link Consumer} で受けるので、Bukkit を起動せずに順序を検証できる。
 */
public class QueuedEventDispatcher implements EventDispatcher, Runnable {
    private final Consumer<Event> sink;
    private final BiConsumer<Event, Throwable> onError;
    private final Queue<Event> pending = new ConcurrentLinkedQueue<>();

    /**
     * @param sink    発火先。実運用では {@code pluginManager::callEvent}
     * @param onError sink が例外を投げた場合の通知先。1 つの失敗で残りを落とさないため
     */
    public QueuedEventDispatcher(Consumer<Event> sink, BiConsumer<Event, Throwable> onError) {
        this.sink = sink;
        this.onError = onError;
    }

    @Override
    public void post(Event event) {
        pending.add(event);
    }

    /** scheduler から毎 tick 呼ばれる。 */
    @Override
    public void run() {
        drain();
    }

    /**
     * 溜まっている event をすべて post 順に発火する。<b>メインスレッドから呼ぶこと。</b>
     *
     * <p>{@code onDisable} からも呼ぶ。{@code onDisable} はメインスレッドで走るので
     * ここでの発火は Bukkit のスレッドチェックを通る（scheduler は既に使えないため、
     * tick task には頼れない）。
     *
     * @return 発火した件数
     */
    public int drain() {
        int dispatched = 0;
        Event event;
        while ((event = pending.poll()) != null) {
            try {
                sink.accept(event);
            } catch (Throwable t) {
                onError.accept(event, t);
            }
            dispatched++;
        }
        return dispatched;
    }

    /** 未発火の event 数。診断用。 */
    public int pendingCount() {
        return pending.size();
    }
}
