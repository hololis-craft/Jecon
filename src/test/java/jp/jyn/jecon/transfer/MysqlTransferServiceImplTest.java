package jp.jyn.jecon.transfer;

import jp.jyn.jecon.testing.DatabaseBackend;
import jp.jyn.jecon.testing.MySqlBackend;
import jp.jyn.jecon.testing.RequiresMySql;

@RequiresMySql
class MysqlTransferServiceImplTest extends AbstractTransferServiceImplTest {
    @Override
    protected DatabaseBackend backend() {
        return new MySqlBackend();
    }
}
