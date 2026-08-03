package jp.jyn.jecon.repository;

import jp.jyn.jecon.db.Database;
import jp.jyn.jecon.testing.TestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AbstractRepository} の書式化と金額判定。
 *
 * <p>format() は Vault の {@code Economy#format} 経由で任意のスレッドから呼ばれるため、
 * 並行呼び出しでも結果が壊れないことを保証する必要がある。
 */
class AbstractRepositoryFormatTest {
    @TempDir
    File dataFolder;

    private Database db;
    private BalanceRepository repository;

    @BeforeEach
    void setUp() {
        db = TestFixture.sqlite(dataFolder);
        TestFixture.ensureSystemAccounts(db);
        repository = new SyncRepository(TestFixture.mainConfig(dataFolder), db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void formatsWithBundledDefaults() {
        // config.yml 既定: "{major} {majorcurrency} {minor} {minorcurrency}" / minorType=asis
        assertEquals("1 dollar 0 cent", repository.format(1.0));
        assertEquals("3 dollars 40 cents", repository.format(3.40));
        // asis は minor をそのまま出すので 1.02 -> "2"（config.yml のコメントどおり）
        assertEquals("1 dollar 2 cents", repository.format(new BigDecimal("1.02")));
    }

    @Test
    void doubleAndDecimalAgree() {
        for (String v : new String[]{"0", "0.01", "1.99", "12345.67", "1000000.5"}) {
            assertEquals(repository.format(new BigDecimal(v)), repository.format(Double.parseDouble(v)),
                "double と BigDecimal の書式が一致すること: " + v);
        }
    }

    /**
     * 共有 NumberFormat / 共有 HashMap を使っていると、並行呼び出しで別スレッドの
     * major/minor が混ざった文字列や例外が出る。
     */
    @Test
    void formatIsThreadSafe() throws Exception {
        int threads = 8;
        int iterations = 2_000;
        // 各スレッドに固有の金額を割り当て、期待文字列と 1 文字でも違えば検出する
        List<Callable<String>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            long cents = 100L * (t + 1) + (t + 1);   // 1.01, 2.02, ...
            BigDecimal amount = BigDecimal.valueOf(cents).scaleByPowerOfTen(-2);
            String expected = repository.format(amount);
            tasks.add(() -> {
                for (int i = 0; i < iterations; i++) {
                    String actual = repository.format(amount);
                    if (!expected.equals(actual)) {
                        return "expected <" + expected + "> but was <" + actual + ">";
                    }
                }
                return null;
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = pool.invokeAll(tasks);
            for (Future<String> f : futures) {
                assertEquals(null, f.get(), "並行 format で結果が壊れた");
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void hasRespectsFractionalAmounts() {
        UUID uuid = UUID.randomUUID();
        assertTrue(repository.createAccount(uuid, new BigDecimal("1.00")));

        // 残高 1.00 に対する判定。小数を切り捨ててから ×100 していると 1.5 が通ってしまう。
        assertTrue(repository.has(uuid, 1.0));
        assertFalse(repository.has(uuid, 1.5), "残高 1.00 で 1.5 は不足");
        assertFalse(repository.has(uuid, new BigDecimal("1.5")));
        assertTrue(repository.has(uuid, new BigDecimal("0.99")));
        assertTrue(repository.has(uuid, 0.99));
    }
}
