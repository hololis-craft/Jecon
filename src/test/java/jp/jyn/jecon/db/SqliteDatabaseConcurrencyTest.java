package jp.jyn.jecon.db;

import jp.jyn.jecon.testing.DatabaseBackend;
import jp.jyn.jecon.testing.SqliteBackend;

class SqliteDatabaseConcurrencyTest extends AbstractDatabaseConcurrencyTest {
    @Override
    protected DatabaseBackend backend() {
        return new SqliteBackend();
    }
}
