package jp.jyn.jecon.event;

import org.bukkit.event.Event;

/**
 * Bukkit event の発火先を抽象化する。
 *
 * <p>Jecon の同期 event は「メインスレッドで、commit 順に発火する」という契約を持つ。
 * 一方で振替は任意のスレッドから呼ばれ得るので、発火するスレッドと呼び出し元の
 * スレッドを切り離す必要がある。その責務をここに閉じ込める。
 *
 * <p>また、これを挟むことで {@code TransferService} などが {@code Jecon} 本体に
 * 依存しなくなり、Bukkit サーバ無しでテストできるようになる。
 */
public interface EventDispatcher {
    /**
     * event を発火する（実装によっては後でメインスレッドから発火する）。
     *
     * <p>呼び出し元のスレッドは問わない。DB のトランザクション内から呼んではいけない
     * （再試行で二重発火する）。
     */
    void post(Event event);

    /** 何もしない実装。テストや、event を必要としない経路向け。 */
    EventDispatcher NOOP = event -> {};
}
