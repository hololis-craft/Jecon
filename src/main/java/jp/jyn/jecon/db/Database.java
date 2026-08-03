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
import java.sql.SQLIntegrityConstraintViolationException;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

public abstract class Database {
    public record TopEntry(int id, long balance, String alias) {}

    protected final HikariDataSource hikari;
    /** Bukkit に依存せずテストから差し替えられるよう、Logger は注入する。 */
    protected final Logger logger;

    protected Database(HikariDataSource hikari, Logger logger) {
        this.hikari = hikari;
        this.logger = logger;
    }

    public static Database connect(MainConfig.DatabaseConfig config) {
        return connect(config, Jecon.getInstance().getLogger());
    }

    public static Database connect(MainConfig.DatabaseConfig config, Logger logger) {
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
        if (config.url.startsWith("jdbc:sqlite:")) {
            logger.info("Use SQLite");
            database = new SQLite(new HikariDataSource(hikariConfig), logger);
        } else if (config.url.startsWith("jdbc:mysql:")) {
            logger.info("Use MySQL");
            hikariConfig.setUsername(config.username);
            hikariConfig.setPassword(config.password);
            database = new MySQL(new HikariDataSource(hikariConfig), logger);
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

    /**
     * 単発クエリ用に connection を貸し出す。{@link SQLException} は
     * {@link RuntimeException} に包み直す（既存の呼び出し規約を維持）。
     */
    protected <T> T withConnection(TxFunction<T> work) {
        try (Connection connection = hikari.getConnection()) {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    abstract protected void migration();

    abstract protected void createTable();

    // ─── account テーブル ────────────────────────────────────────────

    /**
     * UUID から account.id を引く。存在しなければ空。
     */
    public OptionalInt resolveId(UUID uuid) {
        return withConnection(c -> resolveId(c, uuid));
    }

    /** 呼び出し側のトランザクションに参加する {@link #resolveId(UUID)}。 */
    public OptionalInt resolveId(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT `id` FROM `account` WHERE `uuid`=?"
        )) {
            statement.setBytes(1, UUIDBytes.toBytes(uuid));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return OptionalInt.of(resultSet.getInt(1));
                }
            }
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
        return withConnection(c -> getOrCreatePlayerId(c, uuid));
    }

    /** 呼び出し側のトランザクションに参加する {@link #getOrCreatePlayerId(UUID)}。 */
    public int getOrCreatePlayerId(Connection connection, UUID uuid) throws SQLException {
        OptionalInt existing = resolveId(connection, uuid);
        if (existing.isPresent()) {
            return existing.getAsInt();
        }
        String alias = hex(uuid);
        try {
            insertAccount(connection, uuid, alias, true, null);
        } catch (SQLException e) {
            // 並行挿入で UNIQUE 違反した場合の retry
            OptionalInt retry = resolveId(connection, uuid);
            if (retry.isPresent()) {
                return retry.getAsInt();
            }
            throw e;
        }
        return resolveId(connection, uuid)
            .orElseThrow(() -> new IllegalStateException("The ID could not be issued."));
    }

    /**
     * account テーブルに新規行を挿入する。UNIQUE 違反 (uuid / alias 重複) は SQLException を投げる。
     */
    public void insertAccount(UUID uuid, String alias, boolean isPlayer, String namespace) throws SQLException {
        try (Connection connection = hikari.getConnection()) {
            insertAccount(connection, uuid, alias, isPlayer, namespace);
        }
    }

    /** 呼び出し側のトランザクションに参加する {@link #insertAccount(UUID, String, boolean, String)}。 */
    public void insertAccount(Connection connection, UUID uuid, String alias, boolean isPlayer, String namespace)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
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
            bindInstant(statement, 5, Instant.now());
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
        try (Connection connection = hikari.getConnection()) {
            return renameAccount(connection, uuid, newAlias);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** 呼び出し側のトランザクションに参加する {@link #renameAccount(UUID, String)}。 */
    public boolean renameAccount(Connection connection, UUID uuid, String newAlias) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE `account` SET `alias`=? WHERE `uuid`=?"
        )) {
            statement.setString(1, newAlias);
            statement.setBytes(2, UUIDBytes.toBytes(uuid));
            return statement.executeUpdate() != 0;
        } catch (SQLException e) {
            // UNIQUE 違反等は false で吸収し、致命エラーだけ throw
            if (isConstraintViolation(e)) {
                return false;
            }
            throw e;
        }
    }

    /**
     * UNIQUE / PRIMARY KEY 制約違反かどうかを driver 差を吸収して判定する。
     *
     * <p>SQLState の {@code 23xxx} が標準だが、sqlite-jdbc は SQLState を設定せず
     * vendor code だけを返すため driver 側でオーバーライドする。
     */
    protected boolean isConstraintViolation(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
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
        return withConnection(c -> getAccountByUuid(c, uuid));
    }

    /** 呼び出し側のトランザクションに参加する {@link #getAccountByUuid(UUID)}。 */
    public Optional<AccountRecord> getAccountByUuid(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
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
                        readInstant(resultSet, "created_at")
                    ));
                }
            }
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
                        readInstant(resultSet, "created_at")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public List<String> suggestAliases(String prefix, int limit) {
        return suggestAliases(prefix, limit, false);
    }

    public List<String> suggestAliases(String prefix, int limit, boolean playerOnly) {
        List<String> result = new ArrayList<>();
        String sql = playerOnly
            ? "SELECT `alias` FROM `account` WHERE `alias` LIKE ? AND `is_player`=1 ORDER BY `alias` ASC LIMIT ?"
            : "SELECT `alias` FROM `account` WHERE `alias` LIKE ? ORDER BY `alias` ASC LIMIT ?";
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
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
        return withConnection(c -> deleteAccountRow(c, uuid));
    }

    /** 呼び出し側のトランザクションに参加する {@link #deleteAccountRow(UUID)}。 */
    public boolean deleteAccountRow(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM `account` WHERE `uuid`=?"
        )) {
            statement.setBytes(1, UUIDBytes.toBytes(uuid));
            return statement.executeUpdate() != 0;
        }
    }

    /**
     * HikariDataSource へ直接アクセスするための公開ハンドル（{@link jp.jyn.jecon.query.TransactionQueryService}
     * のような同一パッケージ外のクエリ実装から利用）。原則として本クラスのメソッド越しに使うことを推奨。
     */
    public HikariDataSource hikari() {
        return hikari;
    }

    /** {@link Instant} を driver 別の型に bind する（MySQL は Timestamp、SQLite は epoch millis）。 */
    public void bindInstant(PreparedStatement statement, int index, Instant instant) throws SQLException {
        setOccurredAt(statement, index, instant);
    }

    /** ResultSet の対応列を {@link Instant} として読む。driver 別のオーバーライドが可能。 */
    public Instant readInstant(ResultSet resultSet, String column) throws SQLException {
        return Instant.ofEpochMilli(resultSet.getLong(column));
    }

    // ─── transaction helpers ─────────────────────────────────────────

    /**
     * 単一トランザクション内で複数の書き込みを行う。auto-commit を切り替え、例外時に rollback する。
     */
    public void runInTransaction(TxWork work) {
        try (Connection connection = hikari.getConnection()) {
            beginTx(connection);
            boolean committed = false;
            try {
                work.run(connection);
                commitTx(connection);
                committed = true;
            } finally {
                if (!committed) {
                    // rollback 自体の失敗で元の例外を潰さない
                    try {
                        rollbackTx(connection);
                    } catch (SQLException ignored) {
                        // best effort
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * {@link #runInTransaction(TxWork)} の戻り値あり版。
     *
     * <p>{@link RuntimeException} はそのまま伝播する（残高不足などを
     * transactional escape として使う経路のため）。
     */
    public <T> T inTransaction(TxFunction<T> work) {
        try (Connection connection = hikari.getConnection()) {
            beginTx(connection);
            boolean committed = false;
            try {
                T result = work.apply(connection);
                commitTx(connection);
                committed = true;
                return result;
            } finally {
                if (!committed) {
                    try {
                        rollbackTx(connection);
                    } catch (SQLException ignored) {
                        // best effort
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * transient な失敗 (MySQL の deadlock / lock wait timeout、SQLite の
     * {@code SQLITE_BUSY}) を再試行しながらトランザクションを実行する。
     *
     * <p>並行書き込みを許す以上これらは異常系ではなく正常系の一部なので、
     * 書き込み経路は原則こちらを使う。
     *
     * <p><b>work は冪等でなければならない。</b>再試行されるため、DB 以外への
     * 副作用 (event 発火、ログ出力、外部通知) を中に入れてはいけない。
     *
     * @throws TransientDatabaseException 上限まで再試行しても成功しなかった場合
     */
    public <T> T inTransactionWithRetry(TxFunction<T> work) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_TX_ATTEMPTS; attempt++) {
            try {
                return inTransaction(work);
            } catch (RuntimeException e) {
                if (!isRetryable(e)) {
                    throw e;
                }
                last = e;
                if (attempt < MAX_TX_ATTEMPTS) {
                    backoff(attempt);
                }
            }
        }
        throw new TransientDatabaseException(
            "transaction did not succeed after " + MAX_TX_ATTEMPTS + " attempts", last);
    }

    /** 再試行上限。これを超えたら呼び出し元にエラーを返す。 */
    private static final int MAX_TX_ATTEMPTS = 4;

    private static void backoff(int attempt) {
        // 1ms, 2ms, 4ms ... に ±50% のジッタ。tick を跨がない範囲に収める。
        long base = 1L << (attempt - 1);
        long millis = base + ThreadLocalRandom.current().nextLong(base + 1);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientDatabaseException("interrupted while retrying transaction", e);
        }
    }

    private boolean isRetryable(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && isRetryable(sql)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 再試行して意味のある失敗かを driver 差を吸収して判定する。
     * 既定では再試行しない（未知の driver を無闇に再試行しない）。
     */
    protected boolean isRetryable(SQLException e) {
        return false;
    }

    /**
     * トランザクションを開始する。既定は {@code setAutoCommit(false)}。
     * SQLite は deferred BEGIN を避けるためオーバーライドする。
     */
    protected void beginTx(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
    }

    protected void commitTx(Connection connection) throws SQLException {
        connection.commit();
        connection.setAutoCommit(true);
    }

    protected void rollbackTx(Connection connection) throws SQLException {
        connection.rollback();
        connection.setAutoCommit(true);
    }

    @FunctionalInterface
    public interface TxWork {
        void run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface TxFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    /**
     * FOR UPDATE で残高行をロックし、値を返す。MySQL では実際に行ロックが取得される。
     * SQLite では {@code BEGIN IMMEDIATE} により database-level の write lock を
     * トランザクション開始時点で取得しているため、FOR UPDATE 句は不要。
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
     * {@code account} 行をロックし、存在するかを返す。
     *
     * <p>口座に対する一連の操作（残高更新・メンバー権限更新・削除）を直列化するための
     * 単一のロック地点。{@code account} 行は必ず存在するので、MySQL でも
     * gap lock ではなく素の行ロックになる（gap lock 同士は競合しないため、
     * 存在しない行を {@code FOR UPDATE} してから INSERT するとデッドロックし得る）。
     *
     * @return 行が存在すれば true。false の場合ロックは取得されていない
     */
    public boolean lockAccountRow(Connection connection, int id) throws SQLException {
        String sql = supportsSelectForUpdate()
            ? "SELECT `id` FROM `account` WHERE `id`=? FOR UPDATE"
            : "SELECT `id` FROM `account` WHERE `id`=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * トランザクション内の残高上書き。
     *
     * <p><b>行が存在しなければ何もせず false を返す。</b>以前は INSERT で自動作成していたが、
     * それだと口座削除と並行した振替が {@code account} 行の無い {@code balance} 行を
     * 復活させてしまう（孤児行）。{@code balance} 行の生成は明示的な口座作成経路
     * ({@link #createBalance}) だけに限定する。
     *
     * @return 更新できたら true
     */
    public boolean setBalanceInTx(Connection connection, int id, long balance) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
            "UPDATE `balance` SET `balance`=? WHERE `id`=?"
        )) {
            update.setLong(1, balance);
            update.setInt(2, id);
            return update.executeUpdate() != 0;
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
        return withConnection(c -> getMemberPermissions(c, accountId, member));
    }

    /** 呼び出し側のトランザクションに参加する {@link #getMemberPermissions(int, UUID)}。 */
    public int getMemberPermissions(Connection connection, int accountId, UUID member) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT `permissions` FROM `account_member` WHERE `account_id`=? AND `member_uuid`=?"
        )) {
            statement.setInt(1, accountId);
            statement.setBytes(2, UUIDBytes.toBytes(member));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        }
        return -1;
    }

    /**
     * account_member を upsert する。すでに member が居ればビットマスクを上書き。
     */
    /**
     * 権限マスクを指定値で上書きする（無ければ作る）。
     *
     * <p>アプリ側で読んでから書くのではなく upsert 1 文で行う。MySQL の既定分離レベル
     * REPEATABLE READ では、行ロックを取った後の通常の SELECT もトランザクション開始時の
     * スナップショットを返すため、「ロックしてから読んで書く」では他トランザクションが
     * commit した値を見落として lost update する。書き込み時に評価される SQL 式にすれば
     * この問題が原理的に発生しない。
     */
    public boolean setMemberPermissions(Connection connection, int accountId, UUID member, int mask)
        throws SQLException {
        return executeMemberUpsert(connection, sqlMemberSet(), accountId, member, mask, null);
    }

    /** 指定ビットを立てる（無ければそのビットだけを持つ行を作る）。 */
    public boolean addMemberPermissions(Connection connection, int accountId, UUID member, int mask)
        throws SQLException {
        return executeMemberUpsert(connection, sqlMemberOr(), accountId, member, mask, null);
    }

    /** 指定ビットを落とす（無ければ権限 0 の行を作る。従来の実装と同じ挙動）。 */
    public boolean clearMemberPermissions(Connection connection, int accountId, UUID member, int mask)
        throws SQLException {
        return executeMemberUpsert(connection, sqlMemberAndNot(), accountId, member, 0, mask);
    }

    /**
     * 行が無ければ作る。既にあれば何もしない。
     *
     * @return 実際に INSERT したら true
     */
    public boolean insertMemberIfAbsent(Connection connection, int accountId, UUID member, int mask)
        throws SQLException {
        // affected rows では判定できない: mysql-connector-j は既定で CLIENT_FOUND_ROWS を使うため、
        // ON DUPLICATE KEY UPDATE が「変更なし」でも matched 行数 1 を返す。
        // 素の INSERT を試して制約違反を吸収する方が driver 非依存。
        // 重複エラーは statement 単位のロールバックなので、トランザクションは継続できる。
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO `account_member` (`account_id`, `member_uuid`, `permissions`) VALUES (?,?,?)"
        )) {
            statement.setInt(1, accountId);
            statement.setBytes(2, UUIDBytes.toBytes(member));
            statement.setInt(3, mask);
            return statement.executeUpdate() != 0;
        } catch (SQLException e) {
            if (isConstraintViolation(e)) {
                return false;
            }
            throw e;
        }
    }

    private boolean executeMemberUpsert(Connection connection, String sql, int accountId, UUID member,
                                       int insertValue, Integer extraMask) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, accountId);
            statement.setBytes(2, UUIDBytes.toBytes(member));
            statement.setInt(3, insertValue);
            if (extraMask != null) {
                statement.setInt(4, extraMask);
            }
            return statement.executeUpdate() != 0;
        }
    }

    /** {@code permissions} を INSERT 値で上書きする upsert。bind は (account_id, member_uuid, mask)。 */
    protected abstract String sqlMemberSet();

    /** {@code permissions |= } INSERT 値。bind は (account_id, member_uuid, mask)。 */
    protected abstract String sqlMemberOr();

    /** {@code permissions &= ~mask}。bind は (account_id, member_uuid, 0, mask)。 */
    protected abstract String sqlMemberAndNot();


    public boolean removeMember(int accountId, UUID member) {
        return withConnection(c -> removeMember(c, accountId, member));
    }

    /** 呼び出し側のトランザクションに参加する {@link #removeMember(int, UUID)}。 */
    public boolean removeMember(Connection connection, int accountId, UUID member) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM `account_member` WHERE `account_id`=? AND `member_uuid`=?"
        )) {
            statement.setInt(1, accountId);
            statement.setBytes(2, UUIDBytes.toBytes(member));
            return statement.executeUpdate() != 0;
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
        return withConnection(c -> deleteAllMembers(c, accountId));
    }

    /** 呼び出し側のトランザクションに参加する {@link #deleteAllMembers(int)}。 */
    public boolean deleteAllMembers(Connection connection, int accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM `account_member` WHERE `account_id`=?"
        )) {
            statement.setInt(1, accountId);
            return statement.executeUpdate() != 0;
        }
    }

    // ─── balance テーブル ────────────────────────────────────────────

    public OptionalLong getBalance(int id) {
        return withConnection(c -> getBalance(c, id));
    }

    /** 呼び出し側のトランザクションに参加する {@link #getBalance(int)}（ロックは取らない）。 */
    public OptionalLong getBalance(Connection connection, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT `balance` FROM `balance` WHERE `id`=?"
        )) {
            statement.setInt(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return OptionalLong.of(resultSet.getLong(1));
                }
            }
        }
        return OptionalLong.empty();
    }

    public boolean createBalance(int id, long balance) {
        return withConnection(c -> createBalance(c, id, balance));
    }

    /**
     * 呼び出し側のトランザクションに参加する {@link #createBalance(int, long)}。
     * 既に行がある場合は制約違反を吸収して false を返す。
     */
    public boolean createBalance(Connection connection, int id, long balance) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO `balance` VALUES(?,?)"
        )) {
            statement.setInt(1, id);
            statement.setLong(2, balance);
            return statement.executeUpdate() != 0;
        } catch (SQLException e) {
            if (isConstraintViolation(e)) {
                return false;
            }
            throw e;
        }
    }

    public boolean removeBalance(int id) {
        return withConnection(c -> removeBalance(c, id));
    }

    /** 呼び出し側のトランザクションに参加する {@link #removeBalance(int)}。 */
    public boolean removeBalance(Connection connection, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM `balance` WHERE `id`=?"
        )) {
            statement.setInt(1, id);
            return statement.executeUpdate() != 0;
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
        return topWithAliases(limit, offset, false);
    }

    public List<TopEntry> topWithAliases(int limit, int offset, boolean playerOnly) {
        List<TopEntry> result = new ArrayList<>();
        String sql = playerOnly
            ? "SELECT `balance`.`id`,`balance`.`balance`,`account`.`alias` " +
                "FROM `balance` INNER JOIN `account` ON `balance`.`id`=`account`.`id` " +
                "WHERE `account`.`is_player`=1 " +
                "ORDER BY `balance`.`balance` DESC LIMIT ? OFFSET ?"
            : "SELECT `balance`.`id`,`balance`.`balance`,`account`.`alias` " +
                "FROM `balance` LEFT JOIN `account` ON `balance`.`id`=`account`.`id` " +
                "ORDER BY `balance`.`balance` DESC LIMIT ? OFFSET ?";
        try (Connection connection = hikari.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
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
                            bindInstant(prepare, 6, oldDB.readInstant(rs, "created_at"));
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
