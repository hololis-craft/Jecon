package jp.jyn.jecon.testing;

import jp.jyn.jecon.db.Database;

import java.io.File;

/**
 * テストを SQLite と MySQL の両方で回すための抽象。
 *
 * <p>ロック挙動は driver ごとに大きく違う（MySQL は行ロックと gap lock、SQLite は
 * database-level の write lock）ので、並行性のテストは両方で回さないと意味がない。
 */
public interface DatabaseBackend {
    /** 空のスキーマに接続した {@link Database} を返す。テストごとに独立している。 */
    Database connect(File dataFolder);

    /**
     * この driver が「再試行すべき一時的競合」と判定する vendor error code。
     * retry のテストで人工的に発生させるために使う。
     */
    int retryableErrorCode();

    String displayName();
}
