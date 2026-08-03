package jp.jyn.jecon.transfer;

import jp.jyn.jecon.account.AccountService;
import jp.jyn.jecon.account.AccountServiceImpl;
import jp.jyn.jecon.concurrent.MainThreadBridge;
import jp.jyn.jecon.concurrent.MainThreadUnavailableException;
import jp.jyn.jecon.db.Database;
import jp.jyn.jecon.event.EventDispatcher;
import jp.jyn.jecon.modifier.ModifiedTransfer;
import jp.jyn.jecon.modifier.ModifierRegistryImpl;
import jp.jyn.jecon.modifier.TransferModifier;
import jp.jyn.jecon.modifier.TransferProbe;
import jp.jyn.jecon.testing.TestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 非メインスレッドから振替が来たときに Modifier pipeline をどこで実行するか。
 */
class ModifierThreadContractTest {
    @TempDir
    File dataFolder;

    private Database db;
    private AccountService accountService;
    private ModifierRegistryImpl modifiers;
    private UUID from;
    private UUID to;

    /** メインスレッドへの委譲回数を数える差し替え可能な bridge。 */
    private static final class FakeBridge implements MainThreadBridge {
        final AtomicInteger callSyncCount = new AtomicInteger();
        boolean onMainThread;
        boolean available = true;

        @Override
        public boolean isMainThread() {
            return onMainThread;
        }

        @Override
        public <T> T callSync(Supplier<T> work) {
            callSyncCount.incrementAndGet();
            if (!available) {
                throw new MainThreadUnavailableException("test: main thread unavailable", null);
            }
            return work.get();
        }
    }

    private FakeBridge bridge;

    /** Bukkit API を触るふりをする modifier（既定で isThreadSafe() == false）。 */
    private static final class RecordingModifier implements TransferModifier {
        final AtomicInteger invocations = new AtomicInteger();
        private final boolean threadSafe;

        RecordingModifier(boolean threadSafe) {
            this.threadSafe = threadSafe;
        }

        @Override
        public String getId() {
            return "test:recording";
        }

        @Override
        public int getPriority() {
            return 0;
        }

        @Override
        public ModifiedTransfer modify(TransferContext ctx, TransferProbe probe) {
            invocations.incrementAndGet();
            return new ModifiedTransfer.Pass();
        }

        @Override
        public boolean isThreadSafe() {
            return threadSafe;
        }
    }

    @BeforeEach
    void setUp() {
        db = TestFixture.sqlite(dataFolder);
        accountService = new AccountServiceImpl(db, AccountServiceImpl.AccountLifecycleObserver.NOOP);
        modifiers = new ModifierRegistryImpl();
        bridge = new FakeBridge();
        TestFixture.ensureSystemAccounts(db);

        from = UUID.randomUUID();
        to = UUID.randomUUID();
        accountService.createAccount(from, "system:mtfrom", false);
        accountService.createAccount(to, "system:mtto", false);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private TransferService service() {
        return new TransferServiceImpl(db, accountService, modifiers,
            EventDispatcher.NOOP, bridge, TestFixture.quietLogger());
    }

    private TransferResult transfer() {
        return service().transfer(from, to, new BigDecimal("1.00"),
            TransferContext.builder().source("test").withOverdraft().build());
    }

    @Test
    void noModifiersMeansNoThreadCheckAtAll() {
        bridge.onMainThread = false;

        assertInstanceOf(TransferResult.Success.class, transfer());
        assertEquals(0, bridge.callSyncCount.get(),
            "modifier が無いなら hop も判定も不要（hot path にコストを載せない）");
    }

    @Test
    void nonThreadSafeModifierIsRunOnTheMainThread() {
        RecordingModifier modifier = new RecordingModifier(false);
        modifiers.register(modifier);
        bridge.onMainThread = false;

        assertInstanceOf(TransferResult.Success.class, transfer());
        assertEquals(1, bridge.callSyncCount.get(), "メインスレッドへ委譲される");
        assertEquals(1, modifier.invocations.get());
    }

    @Test
    void threadSafeModifierRunsOnTheCallingThread() {
        RecordingModifier modifier = new RecordingModifier(true);
        modifiers.register(modifier);
        bridge.onMainThread = false;

        assertInstanceOf(TransferResult.Success.class, transfer());
        assertEquals(0, bridge.callSyncCount.get(),
            "isThreadSafe() を宣言した modifier は hop 不要");
        assertEquals(1, modifier.invocations.get());
    }

    @Test
    void alreadyOnMainThreadDoesNotHop() {
        RecordingModifier modifier = new RecordingModifier(false);
        modifiers.register(modifier);
        bridge.onMainThread = true;

        assertInstanceOf(TransferResult.Success.class, transfer());
        assertEquals(0, bridge.callSyncCount.get());
        assertEquals(1, modifier.invocations.get());
    }

    /**
     * メインスレッドが使えない（停止処理中など）場合は、modifier を飛ばして
     * 通してしまうのではなく振替自体を拒否する。
     */
    @Test
    void unavailableMainThreadRejectsTheTransferInsteadOfSkippingModifiers() {
        RecordingModifier modifier = new RecordingModifier(false);
        modifiers.register(modifier);
        bridge.onMainThread = false;
        bridge.available = false;

        TransferResult result = transfer();

        TransferResult.Vetoed vetoed = assertInstanceOf(TransferResult.Vetoed.class, result);
        assertEquals("jecon:main-thread-bridge", vetoed.modifierId());
        assertEquals(0, modifier.invocations.get(), "modifier は実行されない");
        // 残高は動いていない
        int fromId = db.resolveId(from).orElseThrow();
        assertTrue(db.getBalance(fromId).orElseThrow() == 0L, "DB に触る前に失敗する");
    }
}
