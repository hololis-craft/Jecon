package jp.jyn.jecon.event;

import jp.jyn.jecon.transfer.AppliedLeg;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueuedEventDispatcherTest {
    private final List<Event> dispatched = new CopyOnWriteArrayList<>();
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();

    private QueuedEventDispatcher dispatcher() {
        return new QueuedEventDispatcher(dispatched::add, (event, error) -> errors.add(error));
    }

    private static JeconTransferCompletedEvent event(long id) {
        return new JeconTransferCompletedEvent(id, Instant.EPOCH, "test",
            Map.of(), null, List.<AppliedLeg>of());
    }

    private static long idOf(Event event) {
        return ((JeconTransferCompletedEvent) event).getTransferId();
    }

    @Test
    void postDoesNotDispatchUntilDrain() {
        QueuedEventDispatcher dispatcher = dispatcher();
        dispatcher.post(event(1));

        assertEquals(1, dispatcher.pendingCount());
        assertTrue(dispatched.isEmpty(), "post だけでは発火しない（メインスレッド以外から呼ばれ得る）");

        assertEquals(1, dispatcher.drain());
        assertEquals(1, dispatched.size());
        assertEquals(0, dispatcher.pendingCount());
    }

    @Test
    void drainPreservesPostOrder() {
        QueuedEventDispatcher dispatcher = dispatcher();
        for (long id = 0; id < 100; id++) {
            dispatcher.post(event(id));
        }

        assertEquals(100, dispatcher.drain());
        for (int i = 0; i < dispatched.size(); i++) {
            assertEquals(i, idOf(dispatched.get(i)), "post 順に発火していない");
        }
    }

    @Test
    void concurrentPostsAreNotLost() throws Exception {
        QueuedEventDispatcher dispatcher = dispatcher();
        int threads = 8;
        int perThread = 500;

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final long base = (long) t * perThread;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        dispatcher.post(event(base + i));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(threads * perThread, dispatcher.drain(), "並行 post で取りこぼしている");
    }

    /** 1 つの listener 例外で残りの event を落とさない。 */
    @Test
    void aFailingEventDoesNotStopTheDrain() {
        RuntimeException boom = new RuntimeException("listener exploded");
        QueuedEventDispatcher dispatcher = new QueuedEventDispatcher(
            event -> {
                if (idOf(event) == 1) {
                    throw boom;
                }
                dispatched.add(event);
            },
            (event, error) -> errors.add(error));

        dispatcher.post(event(0));
        dispatcher.post(event(1));
        dispatcher.post(event(2));

        assertEquals(3, dispatcher.drain());
        assertEquals(List.of(0L, 2L), dispatched.stream().map(QueuedEventDispatcherTest::idOf).toList());
        assertEquals(1, errors.size());
        assertSame(boom, errors.get(0));
    }
}
