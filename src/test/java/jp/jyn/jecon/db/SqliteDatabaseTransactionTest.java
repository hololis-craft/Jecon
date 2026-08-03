package jp.jyn.jecon.db;

import jp.jyn.jecon.testing.DatabaseBackend;
import jp.jyn.jecon.testing.SqliteBackend;

class SqliteDatabaseTransactionTest extends AbstractDatabaseTransactionTest {
    @Override
    protected DatabaseBackend backend() {
        return new SqliteBackend();
    }
}
