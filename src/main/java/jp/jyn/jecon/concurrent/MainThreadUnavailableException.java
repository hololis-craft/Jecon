package jp.jyn.jecon.concurrent;

/**
 * メインスレッドでの実行を依頼したが実行できなかった。
 *
 * <p>プラグインが無効化されている（scheduler が受け付けない）、あるいは待ち時間が
 * 上限を超えた場合に投げる。DB には触っていない段階で発生するので、呼び出し元は
 * 副作用なしの失敗として扱える。
 */
public class MainThreadUnavailableException extends RuntimeException {
    public MainThreadUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
