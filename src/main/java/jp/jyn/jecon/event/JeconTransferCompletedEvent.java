package jp.jyn.jecon.event;

import jp.jyn.jecon.transfer.AppliedLeg;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 振替が成立し、DB commit 直後に発火する。Cancellable ではない。
 */
public class JeconTransferCompletedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final long transferId;
    private final Instant occurredAt;
    private final String source;
    private final Map<String, String> metadata;
    private final UUID actor;
    private final List<AppliedLeg> legs;

    public JeconTransferCompletedEvent(long transferId, Instant occurredAt, String source,
                                       Map<String, String> metadata, UUID actor, List<AppliedLeg> legs) {
        this.transferId = transferId;
        this.occurredAt = occurredAt;
        this.source = source;
        this.metadata = metadata;
        this.actor = actor;
        this.legs = legs;
    }

    public long getTransferId() {
        return transferId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getSource() {
        return source;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public UUID getActor() {
        return actor;
    }

    public List<AppliedLeg> getLegs() {
        return legs;
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
