package jp.jyn.jecon.db;

import jp.jyn.jecon.testing.TestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQLite 固有。WAL でないと reader が writer をブロックし、並行アクセスが直列化する。 */
class SqliteJournalModeTest {
    @TempDir
    File dataFolder;

    @Test
    void connectSwitchesTheDatabaseToWalMode() throws Exception {
        Database db = TestFixture.sqlite(dataFolder);
        try (var connection = db.hikari().getConnection();
             var statement = connection.createStatement();
             var rs = statement.executeQuery("PRAGMA journal_mode")) {
            assertTrue(rs.next());
            assertEquals("wal", rs.getString(1).toLowerCase());
        } finally {
            db.close();
        }
    }
}
