package jp.jyn.jecon.db;

import jp.jyn.jecon.testing.BackendTestBase;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * driver ごとの基本的な CRUD と制約違反の扱い。
 *
 * <p>制約違反の検出は driver 差を吸収するオーバーライドで実装しているので、
 * 両 driver で回す価値がある（sqlite-jdbc は SQLState を設定しない）。
 */
abstract class AbstractDatabaseSmokeTest extends BackendTestBase {

    @Test
    void createsSchemaAndRoundTripsAnAccount() {
        UUID uuid = UUID.randomUUID();
        assertTrue(db.resolveId(uuid).isEmpty(), "未作成の UUID は解決されない");

        int id = db.getOrCreatePlayerId(uuid);
        assertEquals(id, db.resolveId(uuid).orElseThrow());
        assertEquals(uuid, db.getUUID(id).orElseThrow());

        assertTrue(db.createBalance(id, 12_345L));
        assertEquals(12_345L, db.getBalance(id).orElseThrow());

        assertTrue(db.setBalance(id, 500L));
        assertEquals(500L, db.getBalance(id).orElseThrow());

        assertTrue(db.deposit(id, 250L));
        assertEquals(750L, db.getBalance(id).orElseThrow());
    }

    @Test
    void getOrCreatePlayerIdIsIdempotent() {
        UUID uuid = UUID.randomUUID();
        assertEquals(db.getOrCreatePlayerId(uuid), db.getOrCreatePlayerId(uuid));
    }

    @Test
    void aliasResolutionRoundTrips() {
        UUID uuid = UUID.randomUUID();
        int id = db.getOrCreatePlayerId(uuid);
        assertTrue(id > 0);

        assertTrue(db.renameAccount(uuid, "Notch"));
        assertEquals("Notch", db.getAlias(uuid).orElseThrow());
        assertEquals(uuid, db.resolveAlias("Notch").orElseThrow());

        // alias の UNIQUE 違反は例外ではなく false で返る
        UUID other = UUID.randomUUID();
        db.getOrCreatePlayerId(other);
        assertFalse(db.renameAccount(other, "Notch"));
    }

    @Test
    void memberPermissionsRoundTrip() {
        UUID account = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        int id = db.getOrCreatePlayerId(account);

        assertEquals(-1, db.getMemberPermissions(id, member), "未登録は -1");

        assertTrue(inTx(c -> db.insertMemberIfAbsent(c, id, member, 0b101)));
        assertEquals(0b101, db.getMemberPermissions(id, member));

        assertFalse(inTx(c -> db.insertMemberIfAbsent(c, id, member, 0b010)),
            "既存行があれば何もしない");
        assertEquals(0b101, db.getMemberPermissions(id, member));

        // ビット演算は SQL 側で評価される
        assertTrue(inTx(c -> db.addMemberPermissions(c, id, member, 0b010)));
        assertEquals(0b111, db.getMemberPermissions(id, member));

        assertTrue(inTx(c -> db.clearMemberPermissions(c, id, member, 0b101)));
        assertEquals(0b010, db.getMemberPermissions(id, member));

        assertTrue(inTx(c -> db.setMemberPermissions(c, id, member, 0b1000)));
        assertEquals(0b1000, db.getMemberPermissions(id, member), "set は上書き");

        assertTrue(db.removeMember(id, member));
        assertEquals(-1, db.getMemberPermissions(id, member));
    }

    /** 行が無い状態からの upsert も確認する（INSERT 側の分岐）。 */
    @Test
    void permissionBitOpsCreateTheRowWhenAbsent() {
        int id = db.getOrCreatePlayerId(UUID.randomUUID());
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertTrue(inTx(c -> db.addMemberPermissions(c, id, a, 0b100)));
        assertEquals(0b100, db.getMemberPermissions(id, a));

        // 無い行に対するビット落としは、権限 0 の行を作る（従来の挙動）
        assertTrue(inTx(c -> db.clearMemberPermissions(c, id, b, 0b001)));
        assertEquals(0, db.getMemberPermissions(id, b));
    }

    private boolean inTx(TxBool work) {
        return db.inTransaction(work::apply);
    }

    @FunctionalInterface
    private interface TxBool {
        boolean apply(java.sql.Connection connection) throws java.sql.SQLException;
    }
}
