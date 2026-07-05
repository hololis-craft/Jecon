package jp.jyn.jecon.modifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * {@link ModifierRegistry} の実装。優先度昇順のスナップショットを提供する。
 */
public class ModifierRegistryImpl implements ModifierRegistry {
    private final List<TransferModifier> modifiers = new ArrayList<>();

    @Override
    public synchronized void register(TransferModifier modifier) {
        if (modifier == null) {
            throw new IllegalArgumentException("modifier is null");
        }
        // 同じ ID の再登録は上書き扱い
        modifiers.removeIf(m -> m.getId().equals(modifier.getId()));
        modifiers.add(modifier);
        modifiers.sort(Comparator.comparingInt(TransferModifier::getPriority));
    }

    @Override
    public synchronized void unregister(String id) {
        modifiers.removeIf(m -> m.getId().equals(id));
    }

    @Override
    public synchronized List<TransferModifier> registered() {
        return Collections.unmodifiableList(new ArrayList<>(modifiers));
    }
}
