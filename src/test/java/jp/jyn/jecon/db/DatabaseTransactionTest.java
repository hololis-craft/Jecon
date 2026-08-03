package jp.jyn.jecon.db;

import jp.jyn.jecon.testing.TestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link Database#inTransaction} / {@link Database#inTransactionWithRetry} の契約。
 */
class DatabaseTransactionTest {
    /** sqlite-jdbc の {@code SQLITE_BUSY}。driver の isRetryable が true を返す値。 */
    private static final int SQLITE_BUSY = 5;

    @TempDir
    File dataFolder;

    private Database db;
    private int id;

    @BeforeEach
    void setUp() {
        db = TestFixture.sqlite(dataFolder);
        id = db.getOrCreatePlayerId(UUID.randomUUID());
        db.createBalance(id, 1_000L);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void inTransactionCommitsAndReturnsValue() {
        long returned = db.inTransaction(c -> {
            db.setBalanceInTx(c, id, 4_200L);
            return db.selectBalanceForUpdate(c, id).orElseThrow();
        });

        assertEquals(4_200L, returned);
        assertEquals(4_200L, db.getBalance(id).orElseThrow(), "commit された値が読める");
    }

    @Test
    void runtimeExceptionRollsBackAndPropagatesUnwrapped() {
        // 残高不足などを transactional escape に使う経路があるので、
        // RuntimeException は包まずそのまま伝播しなければならない。
        IllegalStateException marker = new IllegalStateException("escape");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> db.inTransaction(c -> {
            db.setBalanceInTx(c, id, 7L);
            throw marker;
        }));

        assertSame(marker, thrown);
        assertEquals(1_000L, db.getBalance(id).orElseThrow(), "rollback されている");
    }

    @Test
    void sqlExceptionRollsBackAndIsWrapped() {
        SQLException cause = new SQLException("boom");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> db.inTransaction(c -> {
            db.setBalanceInTx(c, id, 7L);
            throw cause;
        }));

        assertSame(cause, thrown.getCause());
        assertEquals(1_000L, db.getBalance(id).orElseThrow(), "rollback されている");
    }

    @Test
    void retryEventuallySucceeds() {
        AtomicInteger attempts = new AtomicInteger();

        long result = db.inTransactionWithRetry(c -> {
            if (attempts.incrementAndGet() < 3) {
                throw new SQLException("database is locked", null, SQLITE_BUSY);
            }
            db.setBalanceInTx(c, id, 55L);
            return db.selectBalanceForUpdate(c, id).orElseThrow();
        });

        assertEquals(3, attempts.get(), "2 回失敗して 3 回目で成功");
        assertEquals(55L, result);
        assertEquals(55L, db.getBalance(id).orElseThrow());
    }

    @Test
    void retryGivesUpWithTransientDatabaseException() {
        AtomicInteger attempts = new AtomicInteger();

        TransientDatabaseException thrown = assertThrows(TransientDatabaseException.class,
            () -> db.inTransactionWithRetry(c -> {
                attempts.incrementAndGet();
                throw new SQLException("database is locked", null, SQLITE_BUSY);
            }));

        assertEquals(4, attempts.get(), "上限まで試行する");
        assertInstanceOf(SQLException.class, thrown.getCause().getCause());
        assertEquals(1_000L, db.getBalance(id).orElseThrow(), "残高は変わらない");
    }

    @Test
    void nonRetryableFailureIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> db.inTransactionWithRetry(c -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("permanent");
        }));

        assertEquals(1, attempts.get(), "再試行対象でなければ即座に伝播する");
    }
}
