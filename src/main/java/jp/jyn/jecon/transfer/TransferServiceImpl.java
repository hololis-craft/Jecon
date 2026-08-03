package jp.jyn.jecon.transfer;

import jp.jyn.jecon.account.AccountService;
import jp.jyn.jecon.account.Aliases;
import jp.jyn.jecon.db.Database;
import jp.jyn.jecon.event.EventDispatcher;
import jp.jyn.jecon.event.JeconTransferCompletedEvent;
import jp.jyn.jecon.modifier.ModifiedTransfer;
import jp.jyn.jecon.modifier.ModifierRegistry;
import jp.jyn.jecon.modifier.TransferModifier;
import jp.jyn.jecon.modifier.TransferProbe;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * {@link TransferService} の実装。単一 SQL トランザクションで残高更新と監査ログ INSERT を発行する
 * （ADR-0012 / 03-transfer-api.md）。
 */
public class TransferServiceImpl implements TransferService {
    /** 内部 long 表現は cent (× 100)。 */
    private static final int FRACTIONAL_DIGITS = 2;
    private static final String LEG_LABEL_PRIMARY = "primary";
    private static final int MODIFIER_DEPTH_LIMIT = 5;
    /** {@link #setBalance} の楽観的リトライ上限。 */
    private static final int CAS_ATTEMPTS = 5;
    /** 差分 0 で実際には書き込みが起きなかった場合の transferId。 */
    private static final long NO_OP_TRANSFER_ID = -1L;

    private final Database db;
    private final AccountService accountService;
    private final ModifierRegistry modifierRegistry;
    private final EventDispatcher events;
    private final Logger logger;

    public TransferServiceImpl(Database db, AccountService accountService,
                               ModifierRegistry modifierRegistry, EventDispatcher events, Logger logger) {
        this.db = db;
        this.accountService = accountService;
        this.modifierRegistry = modifierRegistry;
        this.events = events;
        this.logger = logger;
    }

    @Override
    public TransferResult transfer(UUID from, UUID to, BigDecimal amount, TransferContext ctx) {
        return transferBatch(List.of(new TransferLeg(from, to, amount)), ctx);
    }

    @Override
    public TransferResult transferBatch(List<TransferLeg> legs, TransferContext ctx) {
        Objects.requireNonNull(legs, "legs");
        Objects.requireNonNull(ctx, "ctx");
        if (legs.isEmpty()) {
            throw new IllegalArgumentException("legs must not be empty");
        }

        // 金額のスケール検証
        for (TransferLeg leg : legs) {
            TransferResult invalid = validateAmount(leg.amount());
            if (invalid != null) return invalid;
            if (leg.from() == null || leg.to() == null) {
                return new TransferResult.InvalidAmount(leg.amount(), "leg endpoints must not be null");
            }
        }

        List<LegEntry> entries = new ArrayList<>();
        for (TransferLeg leg : legs) {
            entries.add(new LegEntry(leg, LEG_LABEL_PRIMARY));
        }

        TransferResult early = runModifiers(entries, ctx);
        if (early != null) {
            return early;
        }

        // account.id の解決とロックはトランザクション内で行う（executeAtomic）。
        // ここで解決してしまうと、口座削除と競合したときに TOCTOU になる。
        return executeAtomic(entries, ctx, Map.of());
    }

    @Override
    public TransferResult setBalance(UUID account, BigDecimal target, TransferContext ctx) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(ctx, "ctx");
        if (target == null) {
            return new TransferResult.InvalidAmount(null, "target is null");
        }
        if (target.scale() > FRACTIONAL_DIGITS) {
            return new TransferResult.InvalidAmount(target, "amount scale exceeds " + FRACTIONAL_DIGITS);
        }
        long targetRaw = decimalToRaw(target);

        // 楽観的並行制御。「残高を読む → 差分を Modifier pipeline に通す → 適用する」の
        // 一連の流れを、読んだ残高がトランザクション内で変わっていなければ確定させる。
        // 変わっていたら pipeline ごとやり直す（modifier に見せる金額を正しく保つため）。
        for (int attempt = 1; attempt <= CAS_ATTEMPTS; attempt++) {
            OptionalLong observed = readBalance(account);
            if (observed.isEmpty()) {
                return new TransferResult.AccountMissing(account);
            }
            long diff = targetRaw - observed.getAsLong();
            if (diff == 0) {
                return new TransferResult.Success(NO_OP_TRANSFER_ID, Instant.now(), List.of());
            }

            TransferLeg leg = diff > 0
                ? new TransferLeg(Aliases.LEGACY_SOURCE_UUID, account, rawToDecimal(diff))
                : new TransferLeg(account, Aliases.LEGACY_SINK_UUID, rawToDecimal(-diff));

            List<LegEntry> entries = new ArrayList<>();
            entries.add(new LegEntry(leg, LEG_LABEL_PRIMARY));
            TransferResult early = runModifiers(entries, ctx);
            if (early != null) {
                return early;
            }

            try {
                return executeAtomic(entries, ctx, Map.of(account, observed.getAsLong()));
            } catch (StaleReadSignal ignored) {
                // 読んだ残高が変わっていた。差分を作り直す。
            }
        }
        return new TransferResult.Conflict(account);
    }

    private OptionalLong readBalance(UUID account) {
        OptionalInt id = db.resolveId(account);
        if (id.isEmpty()) {
            return OptionalLong.empty();
        }
        return db.getBalance(id.getAsInt());
    }

    /**
     * Modifier pipeline を回す。
     *
     * @return 非 null なら以降の処理を行わずその結果を返す（Veto など）
     */
    private TransferResult runModifiers(List<LegEntry> entries, TransferContext ctx) {
        ProbeImpl probe = new ProbeImpl(entries, ctx.overdraft());
        for (TransferModifier modifier : modifierRegistry.registered()) {
            ModifiedTransfer result = safelyModify(modifier, ctx, probe);
            TransferResult applied = applyModifierResult(modifier, result, entries, 0);
            if (applied != null) {
                return applied;
            }
        }
        return null;
    }

    /**
     * @param expectedBalances 楽観的並行制御用。指定された口座の残高がロック取得後に
     *                         この値と異なっていれば {@link StaleReadSignal} を投げる
     */
    private TransferResult executeAtomic(List<LegEntry> entries, TransferContext ctx,
                                         Map<UUID, Long> expectedBalances) {
        long[] amountsRaw = new long[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            amountsRaw[i] = decimalToRaw(entries.get(i).leg.amount());
        }

        // 登場順に重複を除いた endpoint 一覧
        Set<UUID> endpoints = new LinkedHashSet<>();
        for (LegEntry e : entries) {
            endpoints.add(e.leg.from());
            endpoints.add(e.leg.to());
        }

        TransferResult.Success success;
        try {
            success = db.inTransactionWithRetry(connection -> {
                // 再試行されるので、トランザクション内で毎回取り直す
                Instant occurredAt = Instant.now();

                // 1) id を解決する（ロックはまだ取らない。ロック順序を決めるため）
                Map<UUID, Integer> uuidToId = new LinkedHashMap<>();
                Map<Integer, UUID> idToUuid = new HashMap<>();
                for (UUID uuid : endpoints) {
                    OptionalInt id = db.resolveId(connection, uuid);
                    if (id.isEmpty()) {
                        throw new AccountMissingSignal(uuid);
                    }
                    uuidToId.put(uuid, id.getAsInt());
                    idToUuid.put(id.getAsInt(), uuid);
                }

                // 2) account.id 昇順で account 行 → balance 行をロックする。
                //    AccountService.delete も同じ順序でロックするので、削除と交錯しない。
                List<Integer> lockOrder = new ArrayList<>(new LinkedHashSet<>(uuidToId.values()));
                Collections.sort(lockOrder);
                for (int id : lockOrder) {
                    if (!db.lockAccountRow(connection, id)) {
                        throw new AccountMissingSignal(idToUuid.get(id));
                    }
                }
                Map<Integer, Long> balances = new HashMap<>();
                for (int id : lockOrder) {
                    OptionalLong balance = db.selectBalanceForUpdate(connection, id);
                    if (balance.isEmpty()) {
                        // account 行はあるが経済アカウントを持っていない
                        throw new AccountMissingSignal(idToUuid.get(id));
                    }
                    balances.put(id, balance.getAsLong());
                }

                // ロックを取ってから期待値と比較する（CAS）
                for (Map.Entry<UUID, Long> expected : expectedBalances.entrySet()) {
                    long actual = balances.get(uuidToId.get(expected.getKey()));
                    if (actual != expected.getValue()) {
                        throw new StaleReadSignal();
                    }
                }

                // 3) 各 leg を適用する
                for (int i = 0; i < entries.size(); i++) {
                    LegEntry entry = entries.get(i);
                    long raw = amountsRaw[i];
                    int fromId = uuidToId.get(entry.leg.from());
                    int toId = uuidToId.get(entry.leg.to());

                    long fromBalance = balances.get(fromId);
                    long newFrom = fromBalance - raw;
                    if (!ctx.overdraft() && newFrom < 0) {
                        throw new InsufficientFundsSignal(entry.leg.from(), fromBalance, raw);
                    }
                    balances.put(fromId, newFrom);
                    balances.put(toId, balances.get(toId) + raw);
                }
                for (Map.Entry<Integer, Long> e : balances.entrySet()) {
                    if (!db.setBalanceInTx(connection, e.getKey(), e.getValue())) {
                        throw new AccountMissingSignal(idToUuid.get(e.getKey()));
                    }
                }

                // 4) 監査ログ
                List<AppliedLeg> applied = new ArrayList<>(entries.size());
                Long batchId = null;
                long firstId = -1L;
                for (int i = 0; i < entries.size(); i++) {
                    LegEntry entry = entries.get(i);
                    int fromId = uuidToId.get(entry.leg.from());
                    int toId = uuidToId.get(entry.leg.to());
                    long id = db.insertTransactionLog(connection, occurredAt, ctx.source(),
                        fromId, toId, amountsRaw[i], entry.label, batchId,
                        ctx.actor(), MetadataJson.encode(ctx.metadata()));
                    if (batchId == null) {
                        // 最初の leg の id を batch_id として採用し、以降の leg にも共通付与する。
                        // 最初の leg は自己参照になるため、後段で UPDATE する。
                        batchId = id;
                        firstId = id;
                        db.updateTransactionLogBatchId(connection, id, id);
                    }
                    applied.add(new AppliedLeg(entry.leg.from(), entry.leg.to(), entry.leg.amount(), entry.label));
                }

                return new TransferResult.Success(firstId, occurredAt, List.copyOf(applied));
            });
        } catch (InsufficientFundsSignal signal) {
            return new TransferResult.InsufficientFunds(
                signal.account,
                BigDecimal.valueOf(signal.available).scaleByPowerOfTen(-FRACTIONAL_DIGITS),
                BigDecimal.valueOf(signal.required).scaleByPowerOfTen(-FRACTIONAL_DIGITS)
            );
        } catch (AccountMissingSignal signal) {
            return new TransferResult.AccountMissing(signal.account);
        }

        // event 発火はトランザクションの外。再試行されると二重発火するため中に入れてはいけない。
        events.post(new JeconTransferCompletedEvent(
            success.transferId(), success.occurredAt(), ctx.source(), ctx.metadata(),
            ctx.actor(), success.legs()
        ));
        return success;
    }

    private TransferResult validateAmount(BigDecimal amount) {
        if (amount == null) {
            return new TransferResult.InvalidAmount(null, "amount is null");
        }
        if (amount.signum() < 0) {
            return new TransferResult.InvalidAmount(amount, "amount must be non-negative");
        }
        if (amount.scale() > FRACTIONAL_DIGITS) {
            return new TransferResult.InvalidAmount(amount, "amount scale exceeds " + FRACTIONAL_DIGITS);
        }
        return null;
    }

    private ModifiedTransfer safelyModify(TransferModifier modifier, TransferContext ctx, TransferProbe probe) {
        try {
            ModifiedTransfer r = modifier.modify(ctx, probe);
            return r == null ? new ModifiedTransfer.Pass() : r;
        } catch (RuntimeException e) {
            logger.warning("Modifier '" + modifier.getId() + "' threw, treating as Pass: " + e.getMessage());
            return new ModifiedTransfer.Pass();
        }
    }

    private TransferResult applyModifierResult(TransferModifier modifier, ModifiedTransfer result,
                                               List<LegEntry> entries, int depth) {
        if (result instanceof ModifiedTransfer.Pass) {
            return null;
        }
        if (result instanceof ModifiedTransfer.Veto veto) {
            return new TransferResult.Vetoed(modifier.getId(), veto.reason());
        }
        if (result instanceof ModifiedTransfer.ClampAmount clamp) {
            if (clamp.legIndex() < 0 || clamp.legIndex() >= entries.size()) {
                return null;
            }
            LegEntry old = entries.get(clamp.legIndex());
            entries.set(clamp.legIndex(),
                new LegEntry(new TransferLeg(old.leg.from(), old.leg.to(), clamp.newAmount()), old.label));
            return null;
        }
        if (result instanceof ModifiedTransfer.AdditionalLegs additional) {
            if (depth >= MODIFIER_DEPTH_LIMIT) {
                logger.warning("Modifier depth limit reached; ignoring additional legs from " + modifier.getId());
                return null;
            }
            String label = additional.label() == null ? modifier.getId() : additional.label();
            for (TransferLeg leg : additional.legs()) {
                entries.add(new LegEntry(leg, label));
            }
            return null;
        }
        if (result instanceof ModifiedTransfer.Compound compound) {
            for (ModifiedTransfer part : compound.parts()) {
                TransferResult r = applyModifierResult(modifier, part, entries, depth + 1);
                if (r != null) return r;
            }
            return null;
        }
        return null;
    }

    private static long decimalToRaw(BigDecimal amount) {
        return amount.scaleByPowerOfTen(FRACTIONAL_DIGITS).longValueExact();
    }

    private static BigDecimal rawToDecimal(long raw) {
        return BigDecimal.valueOf(raw).scaleByPowerOfTen(-FRACTIONAL_DIGITS);
    }

    /** {@code List<TransferLeg>} を安全に露出するための box。 */
    static final class LegEntry {
        TransferLeg leg;
        String label;

        LegEntry(TransferLeg leg, String label) {
            this.leg = leg;
            this.label = label;
        }
    }

    private final class ProbeImpl implements TransferProbe {
        private final List<LegEntry> entries;
        private final boolean overdraft;

        ProbeImpl(List<LegEntry> entries, boolean overdraft) {
            this.entries = entries;
            this.overdraft = overdraft;
        }

        @Override
        public List<TransferLeg> legs() {
            List<TransferLeg> out = new ArrayList<>(entries.size());
            for (LegEntry e : entries) out.add(e.leg);
            return Collections.unmodifiableList(out);
        }

        @Override
        public BigDecimal getBalance(UUID account) {
            OptionalInt id = db.resolveId(account);
            if (id.isEmpty()) return BigDecimal.ZERO;
            return db.getBalance(id.getAsInt()).stream()
                .mapToObj(TransferServiceImpl::rawToDecimal)
                .findFirst().orElse(BigDecimal.ZERO);
        }

        @Override
        public boolean isOverdraftAllowed() {
            return overdraft;
        }

        @Override
        public boolean isPlayer(UUID account) {
            return accountService.get(account).map(a -> a.isPlayer()).orElse(false);
        }

        @Override
        public Optional<String> alias(UUID account) {
            return accountService.get(account).map(a -> a.alias());
        }

        @Override
        public boolean hasPermission(UUID account, UUID member, jp.jyn.jecon.account.AccountPermission perm) {
            return accountService.hasPermission(account, member, perm);
        }
    }

    /**
     * 楽観的並行制御の再読み込みを促す。{@code inTransaction} は RuntimeException を
     * 包まずに伝播させるので、そのまま呼び出し元のループまで届く。
     */
    private static final class StaleReadSignal extends RuntimeException {
        StaleReadSignal() {
            super("balance changed between read and lock", null, false, false);
        }
    }

    /** 口座が存在しない（または削除された）ことを transactional escape として使う。 */
    private static final class AccountMissingSignal extends RuntimeException {
        final UUID account;

        AccountMissingSignal(UUID account) {
            super("account missing");
            this.account = account;
        }
    }

    /** 残高不足を transactional escape として使う。 */
    private static final class InsufficientFundsSignal extends RuntimeException {
        final UUID account;
        final long available;
        final long required;

        InsufficientFundsSignal(UUID account, long available, long required) {
            super("insufficient funds");
            this.account = account;
            this.available = available;
            this.required = required;
        }
    }
}
