package jp.jyn.jecon.repository;

import jp.jyn.jecon.config.MainConfig;
import jp.jyn.jecon.db.Database;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.LongFunction;

public abstract class AbstractRepository implements BalanceRepository {
    public final static int FRACTIONAL_DIGITS = 2;
    protected final static int MULTIPLIER = 100;

    /**
     * {@link NumberFormat} ({@code DecimalFormat}) はスレッドセーフではない。
     * format() は Vault の {@code Economy#format} 経由で任意のスレッドから呼ばれるため、
     * インスタンスを共有せずスレッドごとに持つ。
     */
    private static final ThreadLocal<NumberFormat> NUMBER_FORMAT =
        ThreadLocal.withInitial(NumberFormat::getNumberInstance);

    protected final Database db;
    private final MainConfig.FormatConfig formatConfig;
    private final LongFunction<String> minorFormat;

    protected AbstractRepository(MainConfig config, Database db) {
        this.db = db;
        formatConfig = config.format;

        switch (formatConfig.minorType) {
            case OMIT:
                minorFormat = this::minorOmit;
                break;
            case ACCURATE:
                minorFormat = this::minorAccurate;
                break;
            case ASIS:
                minorFormat = String::valueOf;
                break;
            default:
                throw new IllegalStateException();
        }
    }

    private long double2long(double value) {
        return (long) (value * MULTIPLIER);
    }

    private long decimal2long(BigDecimal value) {
        return value.scaleByPowerOfTen(FRACTIONAL_DIGITS).longValue();
    }

    private String format(long value) {
        long major = value / MULTIPLIER;
        long minor = value % MULTIPLIER;
        // 変数 Map は呼び出しごとに作る。共有すると並行呼び出しで別スレッドの値が混ざる。
        Map<String, String> variables = new HashMap<>(8);
        variables.put("major", NUMBER_FORMAT.get().format(major));
        variables.put("minor", minorFormat.apply(minor));
        variables.put("majorcurrency", major > 1 ? formatConfig.pluralMajor : formatConfig.singularMajor);
        variables.put("minorcurrency", minor > 1 ? formatConfig.pluralMinor : formatConfig.singularMinor);

        return (minor == 0 ? formatConfig.formatZeroMinor : formatConfig.format).format(variables);
    }

    private String minorOmit(long minor) {
        if (minor == 0) {
            return "0";
        } else if (minor < 10) {
            return new String(new char[]{'0', (char) (minor + '0')});
        } else if ((minor % 10) == 0) {
            return String.valueOf(minor / 10);
        } else {
            return String.valueOf(minor);
        }
    }

    private String minorAccurate(long minor) {
        if (minor == 0) {
            return "0";
        } else if (minor < 10) {
            return new String(new char[]{'0', (char) (minor + '0')});
        } else {
            return String.valueOf(minor);
        }
    }

    @Override
    public final String format(double value) {
        return format(double2long(value));
    }

    @Override
    public final String format(BigDecimal value) {
        return format(decimal2long(value));
    }

    @Override
    public final Optional<String> format(UUID uuid) {
        OptionalLong balance = getRaw(uuid);
        if (balance.isPresent()) {
            return Optional.of(format(balance.getAsLong()));
        }
        return Optional.empty();
    }

    protected abstract OptionalLong getRaw(UUID uuid);

    @Override
    public final OptionalDouble getDouble(UUID uuid) {
        OptionalLong v = getRaw(uuid);
        if (v.isPresent()) {
            return OptionalDouble.of((double) v.getAsLong() / MULTIPLIER);
        } else {
            return OptionalDouble.empty();
        }
    }

    @Override
    public final Optional<BigDecimal> getDecimal(UUID uuid) {
        OptionalLong v = getRaw(uuid);
        if (v.isPresent()) {
            return Optional.of(BigDecimal.valueOf(v.getAsLong()).scaleByPowerOfTen(-FRACTIONAL_DIGITS));
        } else {
            return Optional.empty();
        }
    }

    protected abstract boolean set(UUID uuid, long balance);

    @Override
    public final boolean set(UUID uuid, double balance) {
        long raw = double2long(balance);
        boolean result = set(uuid, raw);
        return result;
    }

    @Override
    public final boolean set(UUID uuid, BigDecimal balance) {
        long raw = decimal2long(balance);
        boolean result = set(uuid, raw);
        return result;
    }

    private boolean has(UUID uuid, long amount) {
        OptionalLong balance = getRaw(uuid);
        if (!balance.isPresent()) {
            return false;
        }
        return balance.getAsLong() >= amount;
    }

    @Override
    public final boolean has(UUID uuid, double amount) {
        // double2long を通すこと。(long) amount * MULTIPLIER だと小数部を
        // 切り捨ててから乗算するので has(uuid, 1.5) が 1.00 で通ってしまう。
        return has(uuid, double2long(amount));
    }

    @Override
    public final boolean has(UUID uuid, BigDecimal amount) {
        return has(uuid, amount.scaleByPowerOfTen(FRACTIONAL_DIGITS).longValue());
    }

    protected abstract boolean deposit(UUID uuid, long amount);

    @Override
    public final boolean deposit(UUID uuid, double amount) {
        long raw = double2long(amount);
        boolean result = this.deposit(uuid, raw);
        return result;
    }

    @Override
    public final boolean deposit(UUID uuid, BigDecimal amount) {
        long raw = decimal2long(amount);
        boolean result = this.deposit(uuid, raw);
        return result;
    }

    protected boolean withdraw(UUID uuid, long amount) {
        return this.deposit(uuid, -amount); // -n == +-n
    }

    @Override
    public final boolean withdraw(UUID uuid, double amount) {
        long raw = double2long(amount);
        boolean result = this.withdraw(uuid, raw);
        return result;
    }

    @Override
    public final boolean withdraw(UUID uuid, BigDecimal amount) {
        long raw = decimal2long(amount);
        boolean result = this.withdraw(uuid, raw);
        return result;
    }

    @Override
    public final boolean hasAccount(UUID uuid) {
        return getRaw(uuid).isPresent();
    }

    protected abstract boolean createAccount(UUID uuid, long balance);

    public final boolean createAccount(UUID uuid, double balance) {
        long raw = double2long(balance);
        boolean result = createAccount(uuid, raw);
        return result;
    }

    public final boolean createAccount(UUID uuid, BigDecimal balance) {
        long raw = decimal2long(balance);
        boolean result = createAccount(uuid, raw);
        return result;
    }

    @Override
    public final Map<UUID, BigDecimal> top(int limit, int offset) {
        Map<UUID, BigDecimal> result = new LinkedHashMap<>();
        db.top(limit, offset).forEach((id, balance) -> db.getUUID(id).ifPresent(
            uuid -> result.put(uuid, BigDecimal.valueOf(balance).scaleByPowerOfTen(-FRACTIONAL_DIGITS))
        ));
        return result;
    }
}
