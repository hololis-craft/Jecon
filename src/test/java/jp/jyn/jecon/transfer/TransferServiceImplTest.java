package jp.jyn.jecon.transfer;

import jp.jyn.jecon.account.AccountService;
import jp.jyn.jecon.account.AccountServiceImpl;
import jp.jyn.jecon.account.Aliases;
import jp.jyn.jecon.concurrent.MainThreadBridge;
import jp.jyn.jecon.db.Database;
import jp.jyn.jecon.event.EventDispatcher;
import jp.jyn.jecon.modifier.ModifiedTransfer;
import jp.jyn.jecon.modifier.ModifierRegistryImpl;
import jp.jyn.jecon.modifier.TransferModifier;
import jp.jyn.jecon.modifier.TransferProbe;
import jp.jyn.jecon.testing.TestFixture;
import org.bukkit.event.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferServiceImplTest {
    @TempDir
    File dataFolder;

    private Database db;
    private AccountService accountService;
    private TransferService transferService;
    private final Queue<Event> posted = new ConcurrentLinkedQueue<>();
    private ModifierRegistryImpl modifiers;

    @BeforeEach
    void setUp() {
        db = TestFixture.sqlite(dataFolder);
        accountService = new AccountServiceImpl(db, AccountServiceImpl.AccountLifecycleObserver.NOOP);
        modifiers = new ModifierRegistryImpl();
        EventDispatcher dispatcher = posted::add;
        transferService = new TransferServiceImpl(db, accountService, modifiers,
            dispatcher, MainThreadBridge.INLINE, TestFixture.quietLogger());
        TestFixture.ensureSystemAccounts(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private UUID account(String alias, String balance) {
        UUID uuid = UUID.randomUUID();
        accountService.createAccount(uuid, alias, false);
        TransferResult result = transferService.setBalance(uuid, new BigDecimal(balance),
            TransferContext.builder().source("test").withOverdraft().build());
        assertInstanceOf(TransferResult.Success.class, result);
        return uuid;
    }

    private BigDecimal balanceOf(UUID uuid) {
        int id = db.resolveId(uuid).orElseThrow();
        return BigDecimal.valueOf(db.getBalance(id).orElseThrow()).scaleByPowerOfTen(-2);
    }

    private static TransferContext ctx() {
        return TransferContext.builder().source("test").build();
    }

    @Test
    void transferMovesFundsAndPostsAnEvent() {
        UUID from = account("system:from", "100.00");
        UUID to = account("system:to", "0.00");
        posted.clear();

        TransferResult result = transferService.transfer(from, to, new BigDecimal("30.50"), ctx());

        assertInstanceOf(TransferResult.Success.class, result);
        assertEquals(new BigDecimal("69.50"), balanceOf(from));
        assertEquals(new BigDecimal("30.50"), balanceOf(to));
        assertEquals(1, posted.size(), "event は 1 度だけ発火する");
    }

    @Test
    void insufficientFundsLeavesBalancesUntouchedAndPostsNothing() {
        UUID from = account("system:poor", "10.00");
        UUID to = account("system:rich", "0.00");
        posted.clear();

        TransferResult result = transferService.transfer(from, to, new BigDecimal("10.01"), ctx());

        assertInstanceOf(TransferResult.InsufficientFunds.class, result);
        assertEquals(new BigDecimal("10.00"), balanceOf(from));
        assertEquals(new BigDecimal("0.00"), balanceOf(to));
        assertTrue(posted.isEmpty(), "失敗時は event を発火しない");
    }

    @Test
    void transferToDeletedAccountReportsAccountMissing() {
        UUID from = account("system:src", "100.00");
        UUID to = account("system:doomed", "0.00");
        assertTrue(accountService.delete(to));

        TransferResult result = transferService.transfer(from, to, BigDecimal.ONE.setScale(2), ctx());

        assertInstanceOf(TransferResult.AccountMissing.class, result);
        assertEquals(new BigDecimal("100.00"), balanceOf(from), "送金元は変わらない");
    }

    @Test
    void setBalanceReachesTheTargetInBothDirections() {
        UUID uuid = account("system:target", "50.00");
        TransferContext ctx = TransferContext.builder().source("test").withOverdraft().build();

        assertInstanceOf(TransferResult.Success.class,
            transferService.setBalance(uuid, new BigDecimal("120.25"), ctx));
        assertEquals(new BigDecimal("120.25"), balanceOf(uuid));

        assertInstanceOf(TransferResult.Success.class,
            transferService.setBalance(uuid, new BigDecimal("7.00"), ctx));
        assertEquals(new BigDecimal("7.00"), balanceOf(uuid));

        // 差分 0 は書き込まない
        posted.clear();
        assertInstanceOf(TransferResult.Success.class,
            transferService.setBalance(uuid, new BigDecimal("7.00"), ctx));
        assertEquals(new BigDecimal("7.00"), balanceOf(uuid));
        assertTrue(posted.isEmpty(), "no-op では event を発火しない");
    }

    /**
     * setBalance が「残高を読む」と「差分を適用する」の間に割り込まれても、指定した
     * 残高に正しく着地することを決定論的に確認する。
     *
     * <p>最終残高だけを見ても、割り込んだ入金が「setBalance に正当に上書きされた」のか
     * 「stale read のせいで差分を誤った」のかは区別できない。そこで Modifier pipeline を
     * 同期フックとして使う。pipeline は残高読み取りの後・トランザクションの前に走るので、
     * その中で別スレッドの入金を確定させれば、狙った窓に必ず割り込める。
     *
     * <p>楽観的並行制御が無いと、古い残高から求めた差分をそのまま適用してしまい、
     * 最終残高が「目標 + 割り込んだ入金額」になる。
     */
    @Test
    void setBalanceHitsItsTargetEvenIfTheBalanceMovesAfterTheRead() throws Exception {
        TransferContext overdraft = TransferContext.builder().source("test").withOverdraft().build();
        UUID target = account("system:cas", "100.00");

        ExecutorService intruder = Executors.newSingleThreadExecutor();
        try {
            // 最初の pipeline 実行時だけ、別スレッドで 10.00 を入金して確定させる。
            // 再試行時 (2 回目以降) は何もしないのでループが収束する。
            AtomicBoolean fired = new AtomicBoolean();
            modifiers.register(new TransferModifier() {
                @Override
                public String getId() {
                    return "test:intrude-after-read";
                }

                @Override
                public int getPriority() {
                    return 0;
                }

                @Override
                public ModifiedTransfer modify(TransferContext ctx, TransferProbe probe) {
                    if (fired.compareAndSet(false, true)) {
                        try {
                            TransferResult deposited = intruder.submit(() -> transferService.transfer(
                                Aliases.LEGACY_SOURCE_UUID, target, new BigDecimal("10.00"), overdraft
                            )).get(30, TimeUnit.SECONDS);
                            assertInstanceOf(TransferResult.Success.class, deposited);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return new ModifiedTransfer.Pass();
                }
            });

            TransferResult result = transferService.setBalance(target, new BigDecimal("200.00"), overdraft);

            assertInstanceOf(TransferResult.Success.class, result);
            assertTrue(fired.get(), "割り込みが実行されていない（テストが機能していない）");
            assertEquals(0, new BigDecimal("200.00").compareTo(balanceOf(target)),
                "読み取り後に残高が動いたのに、古い差分をそのまま適用している");
        } finally {
            intruder.shutdownNow();
            assertTrue(intruder.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /**
     * setBalance と入金を素朴に並行させる。総額の整合というより、どちらも
     * 例外を出さず Success で終わり、残高が説明可能な値になることの確認。
     */
    @Test
    void concurrentSetBalanceAndDepositBothSucceed() throws Exception {
        int rounds = 40;
        TransferContext overdraft = TransferContext.builder().source("test").withOverdraft().build();
        UUID target = account("system:cas2", "0.00");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                transferService.setBalance(target, new BigDecimal("100.00"), overdraft);

                CountDownLatch start = new CountDownLatch(1);
                Future<TransferResult> setter = pool.submit(() -> {
                    start.await();
                    return transferService.setBalance(target, new BigDecimal("200.00"), overdraft);
                });
                Future<TransferResult> depositor = pool.submit(() -> {
                    start.await();
                    Thread.sleep(ThreadLocalRandom.current().nextInt(2));
                    return transferService.transfer(Aliases.LEGACY_SOURCE_UUID, target,
                        new BigDecimal("10.00"), overdraft);
                });
                start.countDown();

                assertInstanceOf(TransferResult.Success.class, setter.get(30, TimeUnit.SECONDS),
                    "round " + round + ": setBalance が競合で諦めた");
                assertInstanceOf(TransferResult.Success.class, depositor.get(30, TimeUnit.SECONDS));

                // set が後に確定すれば 200、入金が後に確定すれば 210。
                BigDecimal actual = balanceOf(target);
                assertTrue(actual.compareTo(new BigDecimal("200.00")) == 0
                        || actual.compareTo(new BigDecimal("210.00")) == 0,
                    "round " + round + ": 想定外の残高 " + actual);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** 並行振替の総額保存を TransferService の経路で確認する。 */
    @Test
    void concurrentTransfersConserveTotalBalance() throws Exception {
        int accounts = 5;
        List<UUID> uuids = new ArrayList<>();
        for (int i = 0; i < accounts; i++) {
            uuids.add(account("system:conserve" + i, "1000.00"));
        }
        BigDecimal expected = new BigDecimal("1000.00").multiply(BigDecimal.valueOf(accounts));

        int threads = 6;
        int perThread = 40;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ThreadLocalRandom rnd = ThreadLocalRandom.current();
                        int a = rnd.nextInt(accounts);
                        int b = (a + 1 + rnd.nextInt(accounts - 1)) % accounts;
                        BigDecimal amount = BigDecimal.valueOf(rnd.nextLong(1, 2_000), 2);
                        transferService.transfer(uuids.get(a), uuids.get(b), amount, ctx());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        BigDecimal total = BigDecimal.ZERO;
        for (UUID uuid : uuids) {
            total = total.add(balanceOf(uuid));
        }
        assertEquals(0, expected.compareTo(total), "総額が保存されていない: " + total);
    }
}
