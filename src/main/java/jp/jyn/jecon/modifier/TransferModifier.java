package jp.jyn.jecon.modifier;

import jp.jyn.jecon.transfer.TransferContext;

/**
 * 振替の前段に差し込む拡張点。優先度昇順で走る。
 */
public interface TransferModifier {
    String getId();

    int getPriority();

    ModifiedTransfer modify(TransferContext ctx, TransferProbe probe);
}
