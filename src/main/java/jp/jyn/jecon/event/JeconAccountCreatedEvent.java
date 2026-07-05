package jp.jyn.jecon.event;

import jp.jyn.jecon.account.Account;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 口座が新規生成された直後に発火する。Cancellable ではない。
 */
public class JeconAccountCreatedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Account account;
    private final BigDecimal initialBalance;

    public JeconAccountCreatedEvent(Account account, BigDecimal initialBalance) {
        this.account = account;
        this.initialBalance = initialBalance;
    }

    public UUID getUuid() {
        return account.uuid();
    }

    public String getAlias() {
        return account.alias();
    }

    public boolean isPlayer() {
        return account.isPlayer();
    }

    public Account getAccount() {
        return account;
    }

    public BigDecimal getInitialBalance() {
        return initialBalance;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
