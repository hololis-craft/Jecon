package jp.jyn.jecon.concurrent;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Bukkit の scheduler を使う {@link MainThreadBridge}。
 */
public class BukkitMainThreadBridge implements MainThreadBridge {
    /**
     * メインスレッドでの実行を待つ上限。
     *
     * <p>1 tick = 50ms なので、通常は 1 tick 待てば実行される。渋滞や停止処理と
     * 重なった場合に無限に待たないための保険であり、待ち時間の目標値ではない。
     */
    private static final long TIMEOUT_MILLIS = 5_000L;

    private final Plugin plugin;

    public BukkitMainThreadBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isMainThread() {
        return plugin.getServer().isPrimaryThread();
    }

    @Override
    public <T> T callSync(Supplier<T> work) {
        if (isMainThread()) {
            return work.get();
        }

        Future<T> future;
        try {
            future = plugin.getServer().getScheduler().callSyncMethod(plugin, work::get);
        } catch (RuntimeException e) {
            // 無効化中は IllegalPluginAccessException が飛ぶ
            throw new MainThreadUnavailableException("could not schedule work on the main thread", e);
        }

        try {
            return future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(false);
            throw new MainThreadUnavailableException(
                "timed out after " + TIMEOUT_MILLIS + "ms waiting for the main thread", e);
        } catch (ExecutionException e) {
            // work 自身が投げた例外はそのまま呼び出し元へ
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new MainThreadUnavailableException("main thread work failed", e);
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new MainThreadUnavailableException("interrupted while waiting for the main thread", e);
        }
    }
}
