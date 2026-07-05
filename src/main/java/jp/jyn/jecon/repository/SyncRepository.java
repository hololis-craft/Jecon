package jp.jyn.jecon.repository;

import jp.jyn.jecon.account.Aliases;
import jp.jyn.jecon.config.MainConfig;
import jp.jyn.jecon.db.Database;
import jp.jyn.jecon.transfer.TransferContext;
import jp.jyn.jecon.transfer.TransferResult;
import jp.jyn.jecon.transfer.TransferService;

import java.math.BigDecimal;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * TransferService に委譲する {@link BalanceRepository} 実装（ADR-0010）。
 *
 * <p>読み取り (get / has / top / format) は直接 DB を叩く。書き込み
 * (set / deposit / withdraw / createAccount) は {@code system:legacy_source} /
 * {@code system:legacy_sink} を対向とした {@link TransferService#transfer} に翻訳し、
 * 監査ログと Modifier pipeline を通す。
 */
public class SyncRepository extends AbstractRepository {
    /** 内部 long 表現は cent (× 100)。 */
    private static final int FRACTIONAL_DIGITS = 2;

    /** BalanceRepository 経由の deposit の対向口座。 */
    public static final String LEGACY_SOURCE_ALIAS = "system:legacy_source";
    /** BalanceRepository 経由の withdraw の対向口座。 */
    public static final String LEGACY_SINK_ALIAS = "system:legacy_sink";

    public static final UUID LEGACY_SOURCE_UUID = Aliases.uuidFromAlias(LEGACY_SOURCE_ALIAS);
    public static final UUID LEGACY_SINK_UUID = Aliases.uuidFromAlias(LEGACY_SINK_ALIAS);

    /** {@link TransferService} は Jecon.onEnable の順序都合で後から注入する。 */
    private TransferService transferService;

    public SyncRepository(MainConfig config, Database db) {
        super(config, db);
    }

    public void bindTransferService(TransferService transferService) {
        this.transferService = transferService;
    }

    @Override
    protected OptionalLong getRaw(UUID uuid) {
        OptionalInt id = db.resolveId(uuid);
        if (id.isEmpty()) return OptionalLong.empty();
        return db.getBalance(id.getAsInt());
    }

    @Override
    protected boolean set(UUID uuid, long balance) {
        // 差分を計算して transfer に翻訳する。
        if (!hasAccount(uuid)) return false;
        long current = getRaw(uuid).orElse(0L);
        long diff = balance - current;
        if (diff == 0) return true;
        if (diff > 0) {
            return transferSuccess(LEGACY_SOURCE_UUID, uuid, rawToDecimal(diff), "set");
        } else {
            return transferSuccess(uuid, LEGACY_SINK_UUID, rawToDecimal(-diff), "set");
        }
    }

    @Override
    protected boolean deposit(UUID uuid, long amount) {
        if (!hasAccount(uuid)) return false;
        if (amount == 0) return true;
        if (amount < 0) {
            // legacy 経路で負数を受けたら withdraw に読み替える。
            return transferSuccess(uuid, LEGACY_SINK_UUID, rawToDecimal(-amount), "deposit");
        }
        return transferSuccess(LEGACY_SOURCE_UUID, uuid, rawToDecimal(amount), "deposit");
    }

    @Override
    protected boolean withdraw(UUID uuid, long amount) {
        if (!hasAccount(uuid)) return false;
        if (amount == 0) return true;
        if (amount < 0) {
            return transferSuccess(LEGACY_SOURCE_UUID, uuid, rawToDecimal(-amount), "withdraw");
        }
        return transferSuccess(uuid, LEGACY_SINK_UUID, rawToDecimal(amount), "withdraw");
    }

    @Override
    protected boolean createAccount(UUID uuid, long balance) {
        if (hasAccount(uuid)) return false;
        // account 行を確保してから balance を 0 で作る。
        int id = db.getOrCreatePlayerId(uuid);
        if (!db.createBalance(id, 0L)) {
            // 並行で balance 行ができていた場合。
            return false;
        }
        if (balance == 0) return true;
        return transferSuccess(LEGACY_SOURCE_UUID, uuid, rawToDecimal(balance), "createAccount");
    }

    @Override
    public boolean removeAccount(UUID uuid) {
        OptionalInt id = db.resolveId(uuid);
        if (id.isEmpty()) return false;
        return db.removeBalance(id.getAsInt());
    }

    private boolean transferSuccess(UUID from, UUID to, BigDecimal amount, String legacyMethod) {
        if (transferService == null) {
            // 起動途中で TransferService 未 bind の場合は素の DB 書き込みで fallback する。
            return legacyDirect(from, to, amount);
        }
        TransferContext ctx = TransferContext.builder()
            .source("legacy")
            .metadata("legacy_method", legacyMethod)
            .withOverdraft()  // legacy_source/sink は常時 overdraft
            .build();
        TransferResult result = transferService.transfer(from, to, amount, ctx);
        return result instanceof TransferResult.Success;
    }

    /**
     * TransferService bind 前のブートストラップ経路。legacy source/sink は
     * まだ AccountService 経由で作成されていない可能性がある。
     */
    private boolean legacyDirect(UUID from, UUID to, BigDecimal amount) {
        OptionalInt fromId = db.resolveId(from);
        OptionalInt toId = db.resolveId(to);
        if (fromId.isEmpty() || toId.isEmpty()) return false;
        long raw = amount.scaleByPowerOfTen(FRACTIONAL_DIGITS).longValueExact();
        db.deposit(fromId.getAsInt(), -raw);
        db.deposit(toId.getAsInt(), raw);
        return true;
    }

    private static BigDecimal rawToDecimal(long raw) {
        return BigDecimal.valueOf(raw).scaleByPowerOfTen(-FRACTIONAL_DIGITS);
    }
}
