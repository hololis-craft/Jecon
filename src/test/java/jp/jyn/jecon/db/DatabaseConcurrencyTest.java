package jp.jyn.jecon.db;

import jp.jyn.jecon.testing.TestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 並行書き込みの下で残高の総額が保存されることを検証する。
 *
 * <p>lost update があれば総額がずれるので必ず落ちる。async 対応の中核となる不変条件。
 */
class DatabaseConcurrencyTest {
    private static final int ACCOUNTS = 6;
    private static final long INITIAL_BALANCE = 100_000L;
    private static final int THREADS = 8;
    private static final int TRANSFERS_PER_THREAD = 60;

    @TempDir
    File dataFolder;

    private Database db;
    private final List<Integer> ids = new ArrayList<>();

    @BeforeEach
    void setUp() {
        db = TestFixture.sqlite(dataFolder);
        for (int i = 0; i < ACCOUNTS; i++) {
            int id = db.getOrCreatePlayerId(UUID.randomUUID());
            db.createBalance(id, INITIAL_BALANCE);
            ids.add(id);
        }
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private long totalBalance() {
        long total = 0;
        for (int id : ids) {
            total += db.getBalance(id).orElseThrow();
        }
        return total;
    }

    /**
     * {@code TransferServiceImpl.executeAtomic} と同じ形の振替を 1 トランザクションで行う。
     * ロック順序は account.id 昇順。
     */
    private void transfer(int fromId, int toId, long amount) {
        List<Integer> lockOrder = new ArrayList<>(List.of(fromId, toId));
        Collections.sort(lockOrder);

        db.inTransactionWithRetry(connection -> {
            long from = db.selectBalanceForUpdate(connection, lockOrder.get(0)).orElseThrow();
            long to = db.selectBalanceForUpdate(connection, lockOrder.get(1)).orElseThrow();
            long fromBalance = lockOrder.get(0) == fromId ? from : to;
            long toBalance = lockOrder.get(0) == fromId ? to : from;

            db.setBalanceInTx(connection, fromId, fromBalance - amount);
            db.setBalanceInTx(connection, toId, toBalance + amount);
            return null;
        });
    }

    @Test
    void sqliteRunsInWalMode() throws Exception {
        // WAL でないと reader が writer をブロックし、並行アクセスが直列化する。
        try (var connection = db.hikari().getConnection();
             var statement = connection.createStatement();
             var rs = statement.executeQuery("PRAGMA journal_mode")) {
            assertTrue(rs.next());
            assertEquals("wal", rs.getString(1).toLowerCase());
        }
    }

    @Test
    void concurrentTransfersConserveTotalBalance() throws Exception {
        long expected = totalBalance();
        assertEquals(ACCOUNTS * INITIAL_BALANCE, expected);

        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            tasks.add(() -> {
                start.await();
                for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    int a = rnd.nextInt(ids.size());
                    int b = (a + 1 + rnd.nextInt(ids.size() - 1)) % ids.size();
                    transfer(ids.get(a), ids.get(b), rnd.nextLong(1, 500));
                }
                return null;
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<Void> f : futures) {
                f.get(60, TimeUnit.SECONDS);   // 例外はここで表面化する
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(expected, totalBalance(), "並行振替の後も総額は保存される");
    }
}
