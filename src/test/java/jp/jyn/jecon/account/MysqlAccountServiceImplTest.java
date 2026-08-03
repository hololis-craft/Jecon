package jp.jyn.jecon.account;

import jp.jyn.jecon.testing.DatabaseBackend;
import jp.jyn.jecon.testing.MySqlBackend;
import jp.jyn.jecon.testing.RequiresMySql;

@RequiresMySql
class MysqlAccountServiceImplTest extends AbstractAccountServiceImplTest {
    @Override
    protected DatabaseBackend backend() {
        return new MySqlBackend();
    }
}
