# Modifier パイプライン

外部プラグインが振替の前段に差し込むパイプライン。
税、日次キャップ、cap_c 回路ブレーカーなどはすべてここで実装する。
Economy 本体に built-in の Modifier は持たない（[ADR-0005](./adr/0005-transfer-modifier-external-only.md)）。

## TransferModifier

Job プラグインの `JobRewardModifier` と同じ形（[Job spec 06-public-api.md](../../Jobs/spec/06-public-api.md)）。
口座はすべて UUID で扱う（[02-account-model.md](./02-account-model.md)、[ADR-0013](./adr/0013-uuid-primary-alias-secondary.md)）。

```java
public interface TransferModifier {
  String getId();
  int getPriority();
  ModifiedTransfer modify(TransferContext ctx, TransferProbe probe);
}

public interface TransferProbe {
  List<TransferLeg> legs();                              // 現在の leg リスト（read-only）
  BigDecimal        getBalance(UUID account);            // 参照時点の残高
  boolean           isOverdraftAllowed();
  boolean           isPlayer(UUID account);              // Player / 非 Player 判定
  Optional<String>  alias(UUID account);                 // "system:tax_sink" 等の逆引き（表示・比較用途）
  boolean           hasPermission(UUID account, UUID member, AccountPermission perm);
}
```

`isPlayer` と `alias` は Modifier が「Player 宛の leg にだけ日次キャップをかける」「`system:` 系列の対向は素通し」といった判定を書くための補助 API。

### ModifiedTransfer

sealed 型で、Modifier の戻り値を網羅的に扱う。

```java
public sealed interface ModifiedTransfer {
  record Pass() implements ModifiedTransfer {}
  record ClampAmount(int legIndex, BigDecimal newAmount) implements ModifiedTransfer {}
  record Veto(String reason) implements ModifiedTransfer {}
  record AdditionalLegs(List<TransferLeg> legs, String label) implements ModifiedTransfer {}
  record Compound(List<ModifiedTransfer> parts) implements ModifiedTransfer {}
}
```

- `Pass`：この Modifier は何もしない。
- `ClampAmount(i, newAmount)`：`i` 番目の leg の金額を `newAmount` に置き換える。
  0 に clamp すれば実質的な半 veto。
- `Veto(reason)`：全体を中止する。`TransferResult.Vetoed` として呼び出し側に返る。
- `AdditionalLegs`：既存 leg に加えて追加の leg を発行する。税の分離、手数料の徴収に使う。
  `label` は監査ログの `leg_label` になる。
- `Compound`：上記を複数組み合わせる。

## 登録

```java
public interface ModifierRegistry {
  void register(TransferModifier modifier);
  void unregister(String id);
  List<TransferModifier> registered();
}
```

`Jecon.getService(ModifierRegistry.class).register(new MyTaxModifier())` で登録する。
プラグイン disable 時には登録が自動的に解除される（内部で `PluginDisableEvent` を hook）。

## パイプラインの走り方

1. 呼び出し側が `TransferService.transfer(...)` または `transferBatch(...)` を呼ぶ。
2. Economy が `TransferModifier` を priority 昇順で並べる。
3. 各 Modifier に順に `modify` を呼ぶ。
4. 戻り値を評価して現在の leg リストを書き換える。
   `Veto` が返ったら pipeline を打ち切り `Vetoed` を返す。
5. 全 Modifier を通り抜けた最終 leg リストで DB トランザクションを実行する。
6. `JeconTransferCompletedEvent` を発火する。

### 優先度の慣習

数字が小さい方が先。
以下は目安。実装は自由。

- `0〜99`：clamp 系（日次キャップ、cap_c 回路ブレーカー、per-account limit）
- `100〜199`：分離系（税、手数料）
- `200〜299`：veto 系（禁止相手判定、シャドウバン）

Modifier が「clamp 後の金額に対して税を計算したい」場合、税 Modifier は clamp Modifier より後の優先度を割り当てる。

### 追加 leg への Modifier 適用

Modifier が `AdditionalLegs` を返したとき、その追加 leg も同じ pipeline を通す。
無限ループ防止のため、depth 5 まで（config で調整可）。
超過は WARN ログを出し、以降の Modifier は追加 leg のみ無視する。

### 冪等性

Modifier は同じ context・probe に対して同じ結果を返すことを推奨する。
Modifier が状態（キャッシュ、日次カウンタ）を持つ場合、`modify` 内で更新するのは避け、`JeconTransferCompletedEvent` の受信時に確定させる。
理由：pipeline 途中で `Veto` されたときにロールバックしなくて済むため。

## Vault / VaultUnlocked 経由の呼び出しへの適用

旧 Vault、VaultUnlocked（[ADR-0011](./adr/0011-vaultunlocked-shared-account-no-async.md)）のいずれの経路の呼び出しも同じ pipeline を通す（[ADR-0006](./adr/0006-vault-through-modifier-pipeline.md)、[08-vault-bridge.md](./08-vault-bridge.md)）。
`TransferContext.source` は経路別に振られる。

- 旧 Vault 経由：`source = "vault_bridge"`
- VaultUnlocked 経由：`source = "vault_unlocked"`

Modifier が経路を区別したいなら `source.equals("vault_bridge")` や `source.equals("vault_unlocked")` で分岐する。
Vault 系全般に反応させたい場合は、両 source を OR で受ける Modifier を書く。

日次キャップや cap_c のように「Job からの deposit にも Vault 系経由の deposit にもかけたい」ケースは、source の white/black list で判定する Modifier を書く。

`AccountPermission`（VaultUnlocked shared account の権限、[02-account-model.md](./02-account-model.md)）を Modifier から参照したい場合は、`TransferProbe.hasPermission(account, member, perm)` を使う。
ただし permission チェックは呼び出し側の責務が原則で、Modifier では経済ポリシー（税、cap、日次キャップ）に専念する慣習を推奨する。

## 実装例

### 日次キャップ Modifier（外部プラグイン）

```java
public class DailyCapModifier implements TransferModifier {
  public String getId() { return "daily_cap"; }
  public int getPriority() { return 10; }

  public ModifiedTransfer modify(TransferContext ctx, TransferProbe probe) {
    if (!ctx.source().equals("job") && !ctx.source().equals("vault_bridge")) {
      return new Pass();
    }

    List<TransferLeg> legs = probe.legs();
    List<ModifiedTransfer> parts = new ArrayList<>();
    for (int i = 0; i < legs.size(); i++) {
      TransferLeg leg = legs.get(i);
      if (!probe.isPlayer(leg.to())) continue;

      BigDecimal remaining = dailyRemaining(leg.to());
      if (leg.amount().compareTo(remaining) > 0) {
        parts.add(new ClampAmount(i, remaining));
      }
    }
    return parts.isEmpty() ? new Pass() : new Compound(parts);
  }

  private BigDecimal dailyRemaining(UUID uuid) { ... }
}
```

### 税 Modifier（外部プラグイン）

```java
public class SalesTaxModifier implements TransferModifier {
  public String getId() { return "sales_tax"; }
  public int getPriority() { return 100; }

  private final UUID taxSink;

  public SalesTaxModifier(AccountService accounts) {
    this.taxSink = accounts.resolveAlias("system:tax_sink").orElseThrow();
  }

  public ModifiedTransfer modify(TransferContext ctx, TransferProbe probe) {
    if (!ctx.source().equals("shop")) return new Pass();

    List<TransferLeg> taxLegs = probe.legs().stream()
        .filter(l -> probe.isPlayer(l.from()))
        .map(l -> new TransferLeg(l.from(), taxSink,
                                  l.amount().multiply(BigDecimal.valueOf(0.1))))
        .toList();

    return taxLegs.isEmpty() ? new Pass() : new AdditionalLegs(taxLegs, "tax");
  }
}
```

## 関連 ADR

- [ADR-0005 Modifier は外部登録のみ](./adr/0005-transfer-modifier-external-only.md)
- [ADR-0006 Vault も Modifier pipeline を通す](./adr/0006-vault-through-modifier-pipeline.md)
