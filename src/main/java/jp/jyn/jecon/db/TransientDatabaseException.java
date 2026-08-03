package jp.jyn.jecon.db;

/**
 * 一時的な競合 (deadlock / lock timeout / {@code SQLITE_BUSY}) が再試行上限まで
 * 解消しなかったことを表す。
 *
 * <p>データは変更されていない（トランザクションは rollback 済み）ので、呼び出し元は
 * 安全に失敗として扱える。恒久的なエラーと区別するために専用の型にしている。
 */
public class TransientDatabaseException extends RuntimeException {
    public TransientDatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
