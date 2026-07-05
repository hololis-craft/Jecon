package jp.jyn.jecon.transfer;

import jp.jyn.jecon.Jecon;
import jp.jyn.jecon.account.AccountService;
import jp.jyn.jecon.db.Database;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link TransferService} の実装。単一 SQL トランザクションで残高更新と監査ログ INSERT を発行する
 * （ADR-0012 / 03-transfer-api.md）。
 */
public class TransferServiceImpl implements TransferService {
    /** 内部 long 表現は cent (× 100)。 */
    private static final int FRACTIONAL_DIGITS = 2;
    private static final String LEG_LABEL_PRIMARY = "primary";
    private static final int MODIFIER_DEPTH_LIMIT = 5;

    private final Jecon plugin;
    private final Database db;
    private final AccountService accountService;
    private final ModifierRegistry modifierRegistry;

    public TransferServiceImpl(Jecon plugin, Database db, AccountService accountService,
                               ModifierRegistry modifierRegistry) {
        this.plugin = plugin;
        this.db = db;
        this.accountService = accountService;
        this.modifierRegistry = modifierRegistry;
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

        // Modifier pipeline
        List<LegEntry> entries = new ArrayList<>();
        for (TransferLeg leg : legs) {
            entries.add(new LegEntry(leg, LEG_LABEL_PRIMARY));
        }

        ProbeImpl probe = new ProbeImpl(entries, ctx.overdraft());
        for (TransferModifier modifier : modifierRegistry.registered()) {
            ModifiedTransfer result = safelyModify(modifier, ctx, probe);
            TransferResult applied = applyModifierResult(modifier, result, entries, 0);
            if (applied != null) {
                return applied;
            }
        }

        // account.id の解決
        Map<UUID, Integer> uuidToId = new LinkedHashMap<>();
        for (LegEntry e : entries) {
            for (UUID uuid : new UUID[]{e.leg.from(), e.leg.to()}) {
                if (uuidToId.containsKey(uuid)) continue;
                OptionalInt id = db.resolveId(uuid);
                if (id.isEmpty()) {
                    return new TransferResult.AccountMissing(uuid);
                }
                uuidToId.put(uuid, id.getAsInt());
            }
        }

        return executeAtomic(entries, uuidToId, ctx);
    }

    private TransferResult executeAtomic(List<LegEntry> entries, Map<UUID, Integer> uuidToId, TransferContext ctx) {
        long[] amountsRaw = new long[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            amountsRaw[i] = decimalToRaw(entries.get(i).leg.amount());
        }

        // 関与する account.id を昇順にロックする集合
        List<Integer> lockOrder = new ArrayList<>(uuidToId.values());
        Collections.sort(lockOrder);

        AtomicReference<TransferResult.Success> success = new AtomicReference<>();
        AtomicReference<TransferResult> earlyReturn = new AtomicReference<>();
        Instant occurredAt = Instant.now();

        try {
            db.runInTransaction(connection -> {
                Map<Integer, Long> balances = new HashMap<>();
                for (int id : lockOrder) {
                    long balance = db.selectBalanceForUpdate(connection, id).orElse(0L);
                    balances.put(id, balance);
                }
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
                    db.setBalanceInTx(connection, e.getKey(), e.getValue());
                }

                List<AppliedLeg> applied = new ArrayList<>(entries.size());
                Long batchId = null;
                for (int i = 0; i < entries.size(); i++) {
                    LegEntry entry = entries.get(i);
                    int fromId = uuidToId.get(entry.leg.from());
                    int toId = uuidToId.get(entry.leg.to());
                    long id = db.insertTransactionLog(connection, occurredAt, ctx.source(),
                        fromId, toId, amountsRaw[i], entry.label, batchId,
                        ctx.actor(), MetadataJson.encode(ctx.metadata()));
                    if (batchId == null) {
                        // 最初の leg の id を batch_id として採用し、以降の leg にも共通付与する。
                        // 最初の leg は自己参照になるため、必要なら後段で UPDATE する。
                        batchId = id;
                        db.updateTransactionLogBatchId(connection, id, id);
                    }
                    applied.add(new AppliedLeg(entry.leg.from(), entry.leg.to(), entry.leg.amount(), entry.label));
                    if (i == 0) {
                        success.set(new TransferResult.Success(id, occurredAt, applied));
                    }
                }

                // Success の legs は builder 側で全 leg 反映したい
                TransferResult.Success first = success.get();
                success.set(new TransferResult.Success(first.transferId(), occurredAt, List.copyOf(applied)));
            });
        } catch (InsufficientFundsSignal signal) {
            return new TransferResult.InsufficientFunds(
                signal.account,
                BigDecimal.valueOf(signal.available).scaleByPowerOfTen(-FRACTIONAL_DIGITS),
                BigDecimal.valueOf(signal.required).scaleByPowerOfTen(-FRACTIONAL_DIGITS)
            );
        }

        TransferResult early = earlyReturn.get();
        if (early != null) return early;

        TransferResult.Success s = success.get();
        // Bukkit event 発火 (トランザクション外)
        plugin.getServer().getPluginManager().callEvent(
            new JeconTransferCompletedEvent(
                s.transferId(), s.occurredAt(), ctx.source(), ctx.metadata(), ctx.actor(), s.legs()
            )
        );
        return s;
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
            plugin.getLogger().warning("Modifier '" + modifier.getId() + "' threw, treating as Pass: " + e.getMessage());
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
                plugin.getLogger().warning("Modifier depth limit reached; ignoring additional legs from " + modifier.getId());
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
