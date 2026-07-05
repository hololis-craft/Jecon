package jp.jyn.jecon.services;

import java.util.HashMap;
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
    private final Map<Class<?>, Object> registry = new HashMap<>();

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
