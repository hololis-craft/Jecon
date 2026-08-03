package jp.jyn.jecon.testing;

import jp.jyn.jecon.db.Database;

import java.io.File;

/** 一時ディレクトリ上の SQLite に接続する {@link DatabaseBackend}。 */
public final class SqliteBackend implements DatabaseBackend {
    @Override
    public Database connect(File dataFolder) {
        return TestFixture.sqlite(dataFolder);
    }

    @Override
    public int retryableErrorCode() {
        return 5;   // SQLITE_BUSY
    }

    @Override
    public String displayName() {
        return "SQLite";
    }
}
