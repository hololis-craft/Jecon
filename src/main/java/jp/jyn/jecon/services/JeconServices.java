package jp.jyn.jecon.services;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Jecon の公開 Service Locator。
 *
 * <p>{@code Jecon.getInstance().services().get(AccountService.class)} のように取得する。
 * 登録内容はプラグインライフサイクル (enable/disable) に一致するため、
 * disable 中に取得したインスタンスを持ち回さないこと。
 */
public class JeconServices {
    /**
     * {@code get()} は任意のスレッドから呼ばれ得るので並行 Map を使う。
     *
     * <p>reload 中の一瞬だけ service が消える状態を作らないよう、{@code clear()} は
     * {@code onDisable} からのみ呼ばれる前提。
     */
    private final Map<Class<?>, Object> registry = new ConcurrentHashMap<>();

    public <T> void register(Class<T> serviceType, T impl) {
        registry.put(serviceType, impl);
    }

    public void unregister(Class<?> serviceType) {
        registry.remove(serviceType);
    }

    public void clear() {
        registry.clear();
    }

    /**
     * 登録済み service を取得する。未登録なら {@link NoSuchElementException}。
     */
    public <T> T get(Class<T> serviceType) {
        Object impl = registry.get(serviceType);
        if (impl == null) {
            throw new NoSuchElementException("service not registered: " + serviceType.getName());
        }
        return serviceType.cast(impl);
    }
}
