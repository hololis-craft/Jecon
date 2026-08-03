package jp.jyn.jecon.testing;

import jp.jyn.jecon.db.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

/**
 * SQLite / MySQL の両方で同じテストを回すための基底クラス。
 *
 * <p>サブクラスが {@link #backend()} で driver を選ぶ。MySQL 側のサブクラスには
 * {@link RequiresMySql} を付けて、Docker が無い環境では skip させる。
 */
public abstract class BackendTestBase {
    @TempDir
    protected File dataFolder;

    protected Database db;

    protected abstract DatabaseBackend backend();

    @BeforeEach
    void openDatabase() {
        db = backend().connect(dataFolder);
        afterDatabaseOpened();
    }

    /** {@code db} が使える状態になった後の追加セットアップ。 */
    protected void afterDatabaseOpened() {
        // 既定では何もしない
    }

    @AfterEach
    void closeDatabase() {
        if (db != null) {
            db.close();
            db = null;
        }
    }
}
