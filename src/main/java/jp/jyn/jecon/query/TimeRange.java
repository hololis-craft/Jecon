package jp.jyn.jecon.query;

import java.time.Instant;

/**
 * {@link TransactionQueryService} が受ける半開区間 [from, to)。
 */
public record TimeRange(Instant from, Instant to) {
    public static TimeRange between(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from/to must be non-null");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from");
        }
        return new TimeRange(from, to);
    }

    public static TimeRange lastMillis(long millis) {
        Instant to = Instant.now();
        return new TimeRange(to.minusMillis(millis), to);
    }
}
