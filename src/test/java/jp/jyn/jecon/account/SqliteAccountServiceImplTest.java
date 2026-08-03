package jp.jyn.jecon.account;

import jp.jyn.jecon.testing.DatabaseBackend;
import jp.jyn.jecon.testing.SqliteBackend;

class SqliteAccountServiceImplTest extends AbstractAccountServiceImplTest {
    @Override
    protected DatabaseBackend backend() {
        return new SqliteBackend();
    }
}
