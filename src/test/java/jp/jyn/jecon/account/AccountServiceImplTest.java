package jp.jyn.jecon.account;

import jp.jyn.jecon.db.Database;
import jp.jyn.jecon.testing.TestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountServiceImplTest {
    @TempDir
    File dataFolder;

    private Database db;
    private AccountService service;

    @BeforeEach
    void setUp() {
        db = TestFixture.sqlite(dataFolder);
        service = new AccountServiceImpl(db, AccountServiceImpl.AccountLifecycleObserver.NOOP);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    /** account 行が無い balance 行が残っていないか。 */
    private int orphanBalanceRows() {
        try (var connection = db.hikari().getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                 "SELECT COUNT(*) FROM `balance` LEFT JOIN `account`" +
                     " ON `balance`.`id`=`account`.`id` WHERE `account`.`id` IS NULL")) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createAccountAlsoCreatesTheBalanceRow() {
        UUID uuid = UUID.randomUUID();
        service.createAccount(uuid, "system:shop", false);

        int id = db.resolveId(uuid).orElseThrow();
        assertEquals(0L, db.getBalance(id).orElseThrow(),
            "明示的に作られた口座は残高行を持つ（setBalanceInTx は行を自動生成しない）");
    }

    @Test
    void createSharedAccountGrantsOwnerInTheSameTransaction() {
        UUID uuid = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        service.createSharedAccount(uuid, "system:guild", owner);

        assertTrue(service.hasPermission(uuid, owner, AccountPermission.OWNER));
        assertTrue(service.members(uuid).contains(owner));
        int id = db.resolveId(uuid).orElseThrow();
        assertEquals(0L, db.getBalance(id).orElseThrow());
    }

    @Test
    void deleteRemovesAccountBalanceAndMembers() {
        UUID uuid = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        service.createSharedAccount(uuid, "system:gone", owner);
        int id = db.resolveId(uuid).orElseThrow();

        assertTrue(service.delete(uuid));
        assertTrue(db.resolveId(uuid).isEmpty());
        assertTrue(db.getBalance(id).isEmpty());
        assertEquals(0, db.getMemberPermissions(id, owner) + 1, "members も消えている (-1)");
        assertEquals(0, orphanBalanceRows());

        assertFalse(service.delete(uuid), "二重削除は false");
    }

    /**
     * 同じ member の権限ビットを並行に立てる。read-modify-write が
     * account 行のロックで直列化されていないと、立てたビットが取りこぼされる。
     */
    @Test
    void concurrentSetPermissionDoesNotLoseBits() throws Exception {
        UUID account = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        service.createAccount(account, "system:perms", false);
        service.addMember(account, member);

        AccountPermission[] perms = AccountPermission.values();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(perms.length);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (AccountPermission perm : perms) {
                futures.add(pool.submit(() -> {
                    start.await();
                    // 各スレッドが自分の担当ビットだけを何度も立てる
                    for (int i = 0; i < 40; i++) {
                        service.setPermission(account, member, perm, true);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        for (AccountPermission perm : perms) {
            assertTrue(service.hasPermission(account, member, perm),
                "並行更新でビットが失われた: " + perm);
        }
    }

    /**
     * 口座削除と振替（残高書き込み）を並行させても孤児 balance 行が生まれない。
     * setBalanceInTx の INSERT フォールバックがあると、削除後に行が復活する。
     */
    @Test
    void concurrentDeleteAndBalanceWriteLeavesNoOrphanRows() throws Exception {
        int rounds = 60;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                UUID uuid = UUID.randomUUID();
                service.createAccount(uuid, "system:race" + round, false);
                int id = db.resolveId(uuid).orElseThrow();

                CountDownLatch start = new CountDownLatch(1);
                Future<?> deleter = pool.submit(() -> {
                    start.await();
                    return service.delete(uuid);
                });
                Future<?> writer = pool.submit(() -> {
                    start.await();
                    Thread.sleep(ThreadLocalRandom.current().nextInt(2));
                    // 旧 TransferServiceImpl と同じ形: id をトランザクションの外で解決し、
                    // 存在確認せずに残高を書く。setBalanceInTx が行を自動生成していると
                    // 削除済み口座の balance 行が復活する。
                    return db.inTransactionWithRetry(connection ->
                        db.setBalanceInTx(connection, id, 5_000L));
                });
                start.countDown();
                deleter.get(30, TimeUnit.SECONDS);
                writer.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(0, orphanBalanceRows(),
            "account 行の無い balance 行が残った (孤児行)");
    }
}
