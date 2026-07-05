package jp.jyn.jecon.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jp.jyn.jecon.Jecon;
import jp.jyn.jecon.account.AccountRecord;
import jp.jyn.jecon.config.MainConfig;
import jp.jyn.jecon.db.driver.MySQL;
import jp.jyn.jecon.db.driver.SQLite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.logging.Logger;

public abstract class Database {
    public record TopEntry(int id, long balance, String alias) {}

    protected final HikariDataSource hikari;

    protected Database(HikariDataSource hikari) {
        this.hikari = hikari;
    }

    public static Database connect(MainConfig.DatabaseConfig config) {
        HikariConfig hikariConfig = new HikariConfig();

        hikariConfig.setJdbcUrl(config.url);
        hikariConfig.setPoolName("jecon-hikari");
        hikariConfig.setAutoCommit(true);
        hikariConfig.setConnectionInitSql(config.init);
        hikariConfig.setDataSourceProperties(config.properties);

        if (config.maximumPoolSize > 0) {
            hikariConfig.setMaximumPoolSize(config.maximumPoolSize);
        }
        if (config.minimumIdle > 0) {
            hikariConfig.setMinimumIdle(config.minimumIdle);
        }
        if (config.maxLifetime > 0) {
            hikariConfig.setMaxLifetime(config.maxLifetime);
        }
        if (config.connectionTimeout > 0) {
            hikariConfig.setConnectionTimeout(config.connectionTimeout);
        }
        if (config.idleTimeout > 0) {
            hikariConfig.setIdleTimeout(config.idleTimeout);
        }

        Database database;
        Logger logger = Jecon.getInstance().getLogger();
        if (config.url.startsWith("jdbc:sqlite:")) {
            logger.info("Use SQLite");
            database = new SQLite(new HikariDataSource(hikariConfig));
        } else if (config.url.startsWith("jdbc:mysql:")) {
            logger.info("Use MySQL");
            hikariConfig.setUsername(config.username);
            hikariConfig.setPassword(config.password);
            database = new MySQL(new HikariDataSource(hikariConfig));
        } else {
            throw new IllegalArgumentException("Unknown jdbc");
        }

        database.migration();
        database.createTable();
        return database;
    }

    public void close() {
        if (hikari != null) {
            hikari.close();
        }
    }

    abstract protected void migration();

    abstract protected void createTable();

    // ─── account テーブル ────────────────────────────────────────────

    /**
     * UUID から account.id を引く。存在しなければ空。
     */
    public OptionalInt resolveId(UUID uuid) {
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT `id` FROM `account` WHERE `uuid`=?"
             )) {
            statement.setBytes(1, UUIDBytes.toBytes(uuid));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return OptionalInt.of(resultSet.getInt(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return OptionalInt.empty();
    }

    /**
     * UUID から account.id を引き、存在しなければ hex(UUID) の alias で新規作成する。
     *
     * <p>フォールバック alias は Minecraft 名を知らないまま legacy コードが呼び出すケース向け。
     * 実際の名前が判ったら {@link #renameAccount(UUID, String)} で更新する。
     */
    public int getOrCreatePlayerId(UUID uuid) {
        OptionalInt existing = resolveId(uuid);
        if (existing.isPresent()) {
            return existing.getAsInt();
        }
        String alias = hex(uuid);
        try {
            insertAccount(uuid, alias, true, null);
        } catch (SQLException e) {
            // 並行挿入で UNIQUE 違反した場合の retry
            OptionalInt retry = resolveId(uuid);
            if (retry.isPresent()) {
                return retry.getAsInt();
            }
            throw new RuntimeException(e);
        }
        return resolveId(uuid).orElseThrow(() -> new RuntimeException("The ID could not be issued."));
    }

    /**
     * account テーブルに新規行を挿入する。UNIQUE 違反 (uuid / alias 重複) は SQLException を投げる。
     */
    public void insertAccount(UUID uuid, String alias, boolean isPlayer, String namespace) throws SQLException {
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO `account` (`uuid`, `alias`, `is_player`, `namespace`, `created_at`) VALUES (?,?,?,?,?)"
             )) {
            statement.setBytes(1, UUIDBytes.toBytes(uuid));
            statement.setString(2, alias);
            statement.setInt(3, isPlayer ? 1 : 0);
            if (namespace == null) {
                statement.setNull(4, java.sql.Types.VARCHAR);
            } else {
                statement.setString(4, namespace);
            }
            statement.setLong(5, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    public Optional<UUID> getUUID(int id) {
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT `uuid` FROM `account` WHERE `id`=?"
             )) {
            statement.setInt(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(UUIDBytes.fromBytes(resultSet.getBytes(1)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    public Optional<String> getAlias(UUID uuid) {
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT `alias` FROM `account` WHERE `uuid`=?"
             )) {
            statement.setBytes(1, UUIDBytes.toBytes(uuid));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(resultSet.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    /**
     * alias を新しい値へ差し替える。UNIQUE 違反時は false（呼び出し側でハンドリング）。
     */
    public boolean renameAccount(UUID uuid, String newAlias) {
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "UPDATE `account` SET `alias`=? WHERE `uuid`=?"
             )) {
            statement.setString(1, newAlias);
            statement.setBytes(2, UUIDBytes.toBytes(uuid));
            return statement.executeUpdate() != 0;
        } catch (SQLException e) {
            // UNIQUE 違反等は false で吸収し、致命エラーだけ throw
            String state = e.getSQLState();
            if (state != null && (state.startsWith("23") /* integrity constraint */)) {
                return false;
            }
            throw new RuntimeException(e);
        }
    }

    public Optional<UUID> resolveAlias(String alias) {
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT `uuid` FROM `account` WHERE `alias`=?"
             )) {
            statement.setString(1, alias);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(UUIDBytes.fromBytes(resultSet.getBytes(1)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    public Optional<AccountRecord> getAccountByUuid(UUID uuid) {
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT `alias`, `is_player`, `namespace`, `created_at` FROM `account` WHERE `uuid`=?"
             )) {
            statement.setBytes(1, UUIDBytes.toBytes(uuid));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(new AccountRecord(
                        uuid,
                        resultSet.getString("alias"),
                        resultSet.getInt("is_player") != 0,
                        resultSet.getString("namespace"),
                        Instant.ofEpochMilli(resultSet.getLong("created_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    public List<AccountRecord> listAccountsByNamespace(String namespace, int limit, int offset) {
        List<AccountRecord> result = new ArrayList<>();
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT `uuid`, `alias`, `is_player`, `created_at` FROM `account`" +
                     " WHERE `namespace`=? ORDER BY `alias` ASC LIMIT ? OFFSET ?"
             )) {
            statement.setString(1, namespace);
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(new AccountRecord(
                        UUIDBytes.fromBytes(resultSet.getBytes("uuid")),
                        resultSet.getString("alias"),
                        resultSet.getInt("is_player") != 0,
                        namespace,
                        Instant.ofEpochMilli(resultSet.getLong("created_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public List<String> suggestAliases(String prefix, int limit) {
        List<String> result = new ArrayList<>();
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT `alias` FROM `account` WHERE `alias` LIKE ? ORDER BY `alias` ASC LIMIT ?"
             )) {
            statement.setString(1, prefix + "%");
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(resultSet.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public boolean deleteAccountRow(UUID uuid) {
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "DELETE FROM `account` WHERE `uuid`=?"
             )) {
            statement.setBytes(1, UUIDBytes.toBytes(uuid));
            return statement.executeUpdate() != 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ─── transaction helpers ─────────────────────────────────────────

    /**
     * 単一トランザクション内で複数の書き込みを行う。auto-commit を切り替え、例外時に rollback する。
     */
    public void runInTransaction(TxWork work) {
        try (Connection connection = hikari.getConnection()) {
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            } catch (SQLException e) {
                connection.rollback();
                throw new RuntimeException(e);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    public interface TxWork {
        void run(Connection connection) throws SQLException;
    }

    /**
     * FOR UPDATE で残高行をロックし、値を返す。MySQL では実際に行ロックが取得される。
     * SQLite では BEGIN IMMEDIATE 相当により database-level lock で保護されているため、
     * FOR UPDATE 句が無くても安全な同期が取れる。
     */
    public OptionalLong selectBalanceForUpdate(Connection connection, int id) throws SQLException {
        String sql = supportsSelectForUpdate()
            ? "SELECT `balance` FROM `balance` WHERE `id`=? FOR UPDATE"
            : "SELECT `balance` FROM `balance` WHERE `id`=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return OptionalLong.of(rs.getLong(1));
                }
            }
        }
        return OptionalLong.empty();
    }

    /**
     * トランザクション内の残高上書き。存在しない行は INSERT する（0 balance の口座に対する
     * 最初の書き込みで自動作成）。
     */
    public void setBalanceInTx(Connection connection, int id, long balance) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
            "UPDATE `balance` SET `balance`=? WHERE `id`=?"
        )) {
            update.setLong(1, balance);
            update.setInt(2, id);
            if (update.executeUpdate() == 0) {
                try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO `balance` (`id`, `balance`) VALUES (?, ?)"
                )) {
                    insert.setInt(1, id);
                    insert.setLong(2, balance);
                    insert.executeUpdate();
                }
            }
        }
    }

    /**
     * transaction_log に 1 行 INSERT し、生成された id を返す。
     */
    public long insertTransactionLog(Connection connection, Instant occurredAt, String source,
                                     Integer fromId, Integer toId, long amount, String legLabel,
                                     Long batchId, UUID actorUuid, String metadataJson) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO `transaction_log` " +
                "(`occurred_at`, `source`, `from_id`, `to_id`, `amount`, `leg_label`, `batch_id`, `actor_uuid`, `metadata`)" +
                " VALUES (?,?,?,?,?,?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS
        )) {
            setOccurredAt(statement, 1, occurredAt);
            statement.setString(2, source);
            if (fromId == null) statement.setNull(3, java.sql.Types.INTEGER); else statement.setInt(3, fromId);
            if (toId == null) statement.setNull(4, java.sql.Types.INTEGER); else statement.setInt(4, toId);
            statement.setLong(5, amount);
            statement.setString(6, legLabel);
            if (batchId == null) statement.setNull(7, java.sql.Types.BIGINT); else statement.setLong(7, batchId);
            if (actorUuid == null) statement.setNull(8, java.sql.Types.BINARY); else statement.setBytes(8, UUIDBytes.toBytes(actorUuid));
            if (metadataJson == null) statement.setNull(9, java.sql.Types.VARCHAR); else statement.setString(9, metadataJson);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        return -1L;
    }

    /**
     * transaction_log の batch_id を後付けする。単一 leg の場合は自身の id を batch_id にする用途。
     */
    public void updateTransactionLogBatchId(Connection connection, long transferId, long batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE `transaction_log` SET `batch_id`=? WHERE `id`=?"
        )) {
            statement.setLong(1, batchId);
            statement.setLong(2, transferId);
            statement.executeUpdate();
        }
    }

    protected void setOccurredAt(PreparedStatement statement, int index, Instant occurredAt) throws SQLException {
        // デフォルトは epoch millis (SQLite 用)。MySQL は setTimestamp にオーバーライドする。
        statement.setLong(index, occurredAt.toEpochMilli());
    }

    /** MySQL は FOR UPDATE を発行、SQLite は発行しない（database-level lock で十分）。 */
    protected boolean supportsSelectForUpdate() {
        return true;
    }

    // ─── account_member テーブル ─────────────────────────────────────

    public int getMemberPermissions(int accountId, UUID member) {
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT `permissions` FROM `account_member` WHERE `account_id`=? AND `member_uuid`=?"
             )) {
            statement.setInt(1, accountId);
            statement.setBytes(2, UUIDBytes.toBytes(member));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return -1;
    }

    /**
     * account_member を upsert する。すでに member が居ればビットマスクを上書き。
     */
    public boolean upsertMember(int accountId, UUID member, int permissionMask, boolean createOnly) {
        int existing = getMemberPermissions(accountId, member);
        if (existing >= 0) {
            if (createOnly) {
                return false;
            }
            try (Connection connection = hikari.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "UPDATE `account_member` SET `permissions`=? WHERE `account_id`=? AND `member_uuid`=?"
                 )) {
                statement.setInt(1, permissionMask);
                statement.setInt(2, accountId);
                statement.setBytes(3, UUIDBytes.toBytes(member));
                return statement.executeUpdate() != 0;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO `account_member` (`account_id`, `member_uuid`, `permissions`) VALUES (?,?,?)"
             )) {
            statement.setInt(1, accountId);
            statement.setBytes(2, UUIDBytes.toBytes(member));
            statement.setInt(3, permissionMask);
            return statement.executeUpdate() != 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean removeMember(int accountId, UUID member) {
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "DELETE FROM `account_member` WHERE `account_id`=? AND `member_uuid`=?"
             )) {
            statement.setInt(1, accountId);
            statement.setBytes(2, UUIDBytes.toBytes(member));
            return statement.executeUpdate() != 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<UUID> listMembers(int accountId) {
        List<UUID> result = new ArrayList<>();
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT `member_uuid` FROM `account_member` WHERE `account_id`=?"
             )) {
            statement.setInt(1, accountId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(UUIDBytes.fromBytes(resultSet.getBytes(1)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public boolean deleteAllMembers(int accountId) {
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "DELETE FROM `account_member` WHERE `account_id`=?"
             )) {
            statement.setInt(1, accountId);
            return statement.executeUpdate() != 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ─── balance テーブル ────────────────────────────────────────────

    public OptionalLong getBalance(int id) {
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT `balance` FROM `balance` WHERE `id`=?"
             )) {
            statement.setInt(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return OptionalLong.of(resultSet.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return OptionalLong.empty();
    }

    public boolean createBalance(int id, long balance) {
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO `balance` VALUES(?,?)"
             )) {
            statement.setInt(1, id);
            statement.setLong(2, balance);
            return statement.executeUpdate() != 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean removeBalance(int id) {
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "DELETE FROM `balance` WHERE `id`=?"
             )) {
            statement.setInt(1, id);
            return (statement.executeUpdate() != 0);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean setBalance(int id, long balance) {
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "UPDATE `balance` SET `balance`=? WHERE `id`=?"
             )) {
            statement.setLong(1, balance);
            statement.setInt(2, id);
            return (statement.executeUpdate() != 0);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean deposit(int id, long amount) {
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "UPDATE `balance` SET `balance`=`balance`+? WHERE `id`=?"
             )) {
            statement.setLong(1, amount);
            statement.setInt(2, id);
            return (statement.executeUpdate() != 0);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<Integer, Long> top(int limit, int offset) {
        Map<Integer, Long> result = new LinkedHashMap<>();
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT `id`,`balance` FROM `balance` ORDER BY `balance` DESC LIMIT ? OFFSET ?"
             )) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.put(resultSet.getInt("id"), resultSet.getLong("balance"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public List<TopEntry> topWithAliases(int limit, int offset) {
        List<TopEntry> result = new ArrayList<>();
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT `balance`.`id`,`balance`.`balance`,`account`.`alias` " +
                     "FROM `balance` LEFT JOIN `account` ON `balance`.`id`=`account`.`id` " +
                     "ORDER BY `balance`.`balance` DESC LIMIT ? OFFSET ?"
             )) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(new TopEntry(
                        resultSet.getInt("id"),
                        resultSet.getLong("balance"),
                        resultSet.getString("alias")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public void convert(Database oldDB) {
        try (Connection old = oldDB.hikari.getConnection();
             Connection connection = hikari.getConnection()) {
            try {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM `account`");
                    statement.executeUpdate("DELETE FROM `balance`");
                }

                try (Statement statement = old.createStatement();
                     PreparedStatement prepare = connection.prepareStatement(
                         "INSERT INTO `account` (`id`, `uuid`, `alias`, `is_player`, `namespace`, `created_at`) VALUES (?,?,?,?,?,?)"
                     )) {
                    try (ResultSet rs = statement.executeQuery(
                        "SELECT `id`,`uuid`,`alias`,`is_player`,`namespace`,`created_at` FROM `account`"
                    )) {
                        while (rs.next()) {
                            prepare.setInt(1, rs.getInt("id"));
                            prepare.setBytes(2, rs.getBytes("uuid"));
                            prepare.setString(3, rs.getString("alias"));
                            prepare.setInt(4, rs.getInt("is_player"));
                            String ns = rs.getString("namespace");
                            if (ns == null) {
                                prepare.setNull(5, java.sql.Types.VARCHAR);
                            } else {
                                prepare.setString(5, ns);
                            }
                            prepare.setLong(6, rs.getLong("created_at"));
                            prepare.addBatch();
                        }
                        prepare.executeBatch();
                    }
                }

                try (Statement statement = old.createStatement();
                     PreparedStatement prepare = connection.prepareStatement(
                         "INSERT INTO `balance` VALUES(?,?)"
                     )) {
                    try (ResultSet rs = statement.executeQuery("SELECT `id`,`balance` FROM `balance`")) {
                        while (rs.next()) {
                            prepare.setInt(1, rs.getInt("id"));
                            prepare.setLong(2, rs.getLong("balance"));
                            prepare.addBatch();
                        }
                        prepare.executeBatch();
                    }
                }
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static String hex(UUID uuid) {
        byte[] bytes = UUIDBytes.toBytes(uuid);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
