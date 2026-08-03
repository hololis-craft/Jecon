package jp.jyn.jecon.transfer;

import jp.jyn.jecon.testing.DatabaseBackend;
import jp.jyn.jecon.testing.SqliteBackend;

class SqliteTransferServiceImplTest extends AbstractTransferServiceImplTest {
    @Override
    protected DatabaseBackend backend() {
        return new SqliteBackend();
    }
}
