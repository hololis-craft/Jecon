package jp.jyn.jecon.modifier;

import jp.jyn.jecon.transfer.TransferLeg;

import java.math.BigDecimal;
import java.util.List;

/**
 * Modifier の戻り値。sealed で網羅的に処理する。
 */
public sealed interface ModifiedTransfer {

    /** この Modifier では leg を書き換えない。 */
    record Pass() implements ModifiedTransfer {}

    /** {@code legIndex} 番目の leg の金額を {@code newAmount} に置き換える。 */
    record ClampAmount(int legIndex, BigDecimal newAmount) implements ModifiedTransfer {}

    /** 全体を中止して {@code TransferResult.Vetoed} を返させる。 */
    record Veto(String reason) implements ModifiedTransfer {}

    /**
     * 既存 leg に加えて追加の leg を発行する。{@code label} は監査ログの {@code leg_label}。
     */
    record AdditionalLegs(List<TransferLeg> legs, String label) implements ModifiedTransfer {}

    /** 複数の Modifier 変更を組み合わせる。 */
    record Compound(List<ModifiedTransfer> parts) implements ModifiedTransfer {}
}
