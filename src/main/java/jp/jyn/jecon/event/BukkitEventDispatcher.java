package jp.jyn.jecon.event;

import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * event をキューに積み、メインスレッドから毎 tick まとめて発火する {@link EventDispatcher}。
 *
 * <p>Bukkit は同期 event を非メインスレッドから発火すると {@code IllegalStateException} を
 * 投げるので、任意のスレッドから振替を受けるにはスレッドの受け渡しが必要になる。
 *
 * <p><b>メインスレッドからの post も即時発火せずキューに積む。</b>そうしないと、
 * 先に commit された非同期の振替より後の同期の振替が先に届き、event の順序が
 * commit 順とずれる。代償として、メインスレッドの振替でも event は次の tick 以降に
 * なり、呼び出し元の {@code transfer()} が返った後に発火する。
 *
 * <p>キューは commit 順に並ぶので、購読側は event を発生順に受け取れる。
 */
public class BukkitEventDispatcher implements EventDispatcher, Runnable {
    private final Plugin plugin;
    private final Queue<Event> pending = new ConcurrentLinkedQueue<>();

    public BukkitEventDispatcher(Plugin plugin) {
        this.plugin = plugin;
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
     * 溜まっている event をすべて発火する。<b>メインスレッドから呼ぶこと。</b>
     *
     * <p>{@code onDisable} からも呼ぶ。{@code onDisable} はメインスレッドで走るので
     * ここでの発火は Bukkit のスレッドチェックを通る（scheduler は既に使えないため、
     * tick task には頼れない）。
     */
    public void drain() {
        Event event;
        while ((event = pending.poll()) != null) {
            try {
                plugin.getServer().getPluginManager().callEvent(event);
            } catch (Throwable t) {
                // 1 つの event の失敗で残りを落とさない
                plugin.getLogger().log(Level.SEVERE,
                    "Failed to dispatch " + event.getEventName(), t);
            }
        }
    }

    /** 未発火の event 数。診断用。 */
    public int pendingCount() {
        return pending.size();
    }
}
