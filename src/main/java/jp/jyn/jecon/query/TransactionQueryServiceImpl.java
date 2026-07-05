package jp.jyn.jecon.query;

import jp.jyn.jecon.db.Database;
import jp.jyn.jecon.db.UUIDBytes;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * {@link TransactionQueryService} の実装。DB を直接引く。
 */
public class TransactionQueryServiceImpl implements TransactionQueryService {
    private static final int FRACTIONAL_DIGITS = 2;

    private final Database db;

    public TransactionQueryServiceImpl(Database db) {
        this.db = db;
    }

    @Override
    public long countBySource(String source, TimeRange range) {
        String sql = "SELECT COUNT(*) FROM `transaction_log` WHERE `source`=? AND `occurred_at` >= ? AND `occurred_at` < ?";
        try (Connection connection = db.hikari().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, source);
            db.bindInstant(statement, 2, range.from());
            db.bindInstant(statement, 3, range.to());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public BigDecimal sumBySource(String source, TimeRange range) {
        String sql = "SELECT COALESCE(SUM(`amount`),0) FROM `transaction_log`" +
            " WHERE `source`=? AND `occurred_at` >= ? AND `occurred_at` < ?";
        try (Connection connection = db.hikari().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, source);
            db.bindInstant(statement, 2, range.from());
            db.bindInstant(statement, 3, range.to());
            try (ResultSet rs = statement.executeQuery()) {
                long raw = rs.next() ? rs.getLong(1) : 0L;
                return BigDecimal.valueOf(raw).scaleByPowerOfTen(-FRACTIONAL_DIGITS);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public BigDecimal netFlow(UUID account, TimeRange range) {
        OptionalInt id = db.resolveId(account);
        if (id.isEmpty()) return BigDecimal.ZERO;
        int accountId = id.getAsInt();

        String sql = "SELECT" +
            " COALESCE(SUM(CASE WHEN `to_id`=? THEN `amount` ELSE 0 END),0)" +
            " - COALESCE(SUM(CASE WHEN `from_id`=? THEN `amount` ELSE 0 END),0)" +
            " FROM `transaction_log`" +
            " WHERE (`from_id`=? OR `to_id`=?) AND `occurred_at` >= ? AND `occurred_at` < ?";
        try (Connection connection = db.hikari().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            statement.setInt(2, accountId);
            statement.setInt(3, accountId);
            statement.setInt(4, accountId);
            db.bindInstant(statement, 5, range.from());
            db.bindInstant(statement, 6, range.to());
            try (ResultSet rs = statement.executeQuery()) {
                long raw = rs.next() ? rs.getLong(1) : 0L;
                return BigDecimal.valueOf(raw).scaleByPowerOfTen(-FRACTIONAL_DIGITS);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<TransactionRow> recent(UUID account, int limit) {
        OptionalInt id = db.resolveId(account);
        if (id.isEmpty()) return List.of();
        int accountId = id.getAsInt();

        String sql = "SELECT `t`.`id`, `t`.`occurred_at`, `t`.`source`, `t`.`amount`," +
            " `t`.`leg_label`, `t`.`batch_id`, `t`.`actor_uuid`, `t`.`metadata`," +
            " `af`.`uuid` AS `from_uuid`, `at`.`uuid` AS `to_uuid`" +
            " FROM `transaction_log` `t`" +
            " LEFT JOIN `account` `af` ON `t`.`from_id`=`af`.`id`" +
            " LEFT JOIN `account` `at` ON `t`.`to_id`=`at`.`id`" +
            " WHERE `t`.`from_id`=? OR `t`.`to_id`=?" +
            " ORDER BY `t`.`occurred_at` DESC LIMIT ?";
        List<TransactionRow> result = new ArrayList<>();
        try (Connection connection = db.hikari().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            statement.setInt(2, accountId);
            statement.setInt(3, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(readRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    @Override
    public BigDecimal sumByMetadata(String source, String metadataKey, String metadataValue, TimeRange range) {
        // functional index 無し前提の table scan 実装。JSON 走査は driver 依存を避けるため、
        // アプリ側で LIKE + JSON 検査に留める。過大なデータでは性能劣化を許容する。
        String pattern = "%\"" + escape(metadataKey) + "\":\"" + escape(metadataValue) + "\"%";
        String sql = "SELECT COALESCE(SUM(`amount`),0) FROM `transaction_log`" +
            " WHERE `source`=? AND `occurred_at` >= ? AND `occurred_at` < ?" +
            " AND `metadata` LIKE ?";
        try (Connection connection = db.hikari().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, source);
            db.bindInstant(statement, 2, range.from());
            db.bindInstant(statement, 3, range.to());
            statement.setString(4, pattern);
            try (ResultSet rs = statement.executeQuery()) {
                long raw = rs.next() ? rs.getLong(1) : 0L;
                return BigDecimal.valueOf(raw).scaleByPowerOfTen(-FRACTIONAL_DIGITS);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private TransactionRow readRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        Instant occurredAt = db.readInstant(rs, "occurred_at");
        String source = rs.getString("source");
        long amountRaw = rs.getLong("amount");
        BigDecimal amount = BigDecimal.valueOf(amountRaw).scaleByPowerOfTen(-FRACTIONAL_DIGITS);
        String legLabel = rs.getString("leg_label");
        long batchIdRaw = rs.getLong("batch_id");
        Long batchId = rs.wasNull() ? null : batchIdRaw;
        byte[] actorBytes = rs.getBytes("actor_uuid");
        UUID actor = actorBytes == null ? null : UUIDBytes.fromBytes(actorBytes);
        String metadata = rs.getString("metadata");
        byte[] fromBytes = rs.getBytes("from_uuid");
        byte[] toBytes = rs.getBytes("to_uuid");
        UUID from = fromBytes == null ? null : UUIDBytes.fromBytes(fromBytes);
        UUID to = toBytes == null ? null : UUIDBytes.fromBytes(toBytes);
        return new TransactionRow(id, occurredAt, source, from, to, amount, legLabel, batchId, actor, metadata);
    }

    private static String escape(String s) {
        // SQL LIKE と JSON escaping の最小限。key/value は Jecon 内部でしか使わないため、
        // % / _ の混入は想定薄い。混入したら false match するだけで安全側。
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
