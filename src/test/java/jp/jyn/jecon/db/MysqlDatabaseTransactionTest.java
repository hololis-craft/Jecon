package jp.jyn.jecon.db;

import jp.jyn.jecon.testing.DatabaseBackend;
import jp.jyn.jecon.testing.MySqlBackend;
import jp.jyn.jecon.testing.RequiresMySql;

@RequiresMySql
class MysqlDatabaseTransactionTest extends AbstractDatabaseTransactionTest {
    @Override
    protected DatabaseBackend backend() {
        return new MySqlBackend();
    }
}
