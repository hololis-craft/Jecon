package jp.jyn.jecon.db;

import jp.jyn.jecon.testing.TestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * テスト基盤が Bukkit サーバ無しで動くことを確認する最小テスト。
 */
class DatabaseSmokeTest {
    @TempDir
    File dataFolder;

    private Database db;

    @BeforeEach
    void setUp() {
        db = TestFixture.sqlite(dataFolder);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

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
        assertTrue(db.upsertMember(id, member, 0b101, true));
        assertEquals(0b101, db.getMemberPermissions(id, member));

        assertFalse(db.upsertMember(id, member, 0b010, true), "createOnly では既存を上書きしない");
        assertEquals(0b101, db.getMemberPermissions(id, member));

        assertTrue(db.upsertMember(id, member, 0b010, false));
        assertEquals(0b010, db.getMemberPermissions(id, member));

        assertTrue(db.removeMember(id, member));
        assertEquals(-1, db.getMemberPermissions(id, member));
    }
}
