package jp.jyn.jecon.modifier;

import java.util.List;

/**
 * {@link TransferModifier} の登録・解除・列挙 API。
 */
public interface ModifierRegistry {
    void register(TransferModifier modifier);

    void unregister(String id);

    List<TransferModifier> registered();
}
