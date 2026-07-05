package jp.jyn.jecon.vault;

import org.bukkit.plugin.java.PluginClassLoader;

import java.util.Optional;

/**
 * StackWalker で Vault 呼び出し元プラグインを best-effort に推定する
 * （08-vault-bridge.md）。失敗時は {@code "unknown"} を返す。
 */
public final class VaultCallerGuess {
    /** 検索する stack frame の上限。 */
    public static final int DEFAULT_MAX_DEPTH = 20;

    private VaultCallerGuess() {}

    public static String guess() {
        return guess(DEFAULT_MAX_DEPTH);
    }

    public static String guess(int maxDepth) {
        return findPluginName(maxDepth).orElse("unknown");
    }

    private static Optional<String> findPluginName(int maxDepth) {
        try {
            return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames
                    .limit(maxDepth)
                    .map(StackWalker.StackFrame::getDeclaringClass)
                    .filter(c -> c.getClassLoader() instanceof PluginClassLoader)
                    .map(c -> ((PluginClassLoader) c.getClassLoader()).getPlugin())
                    .filter(p -> p != null)
                    .map(p -> p.getName())
                    .filter(name -> !"Jecon".equals(name) && !"Vault".equals(name))
                    .findFirst());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
