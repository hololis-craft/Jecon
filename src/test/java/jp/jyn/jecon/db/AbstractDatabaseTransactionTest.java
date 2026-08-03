package jp.jyn.jecon.db;

import jp.jyn.jecon.testing.BackendTestBase;
import org.junit.jupiter.api.Test;

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
abstract class AbstractDatabaseTransactionTest extends BackendTestBase {
    private int id;

    @Override
    protected void afterDatabaseOpened() {
        id = db.getOrCreatePlayerId(UUID.randomUUID());
        db.createBalance(id, 1_000L);
    }

    /** driver が「再試行すべき」と判定する SQLException を作る。 */
    private SQLException retryable() {
        return new SQLException("transient conflict", null, backend().retryableErrorCode());
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
                throw retryable();
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
                throw retryable();
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
