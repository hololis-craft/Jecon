# 振替 API

Jecon の既存 API は `deposit`、`withdraw`、`set` の片側操作のみで、from と to をペアにした概念がない。
本仕様では `transfer` を一級市民として追加する（[ADR-0004](./adr/0004-transfer-as-primary-api.md)）。
口座は UUID で指定する（[02-account-model.md](./02-account-model.md)、[ADR-0013](./adr/0013-uuid-primary-alias-secondary.md)）。

## 単一 leg 振替

```java
public interface TransferService {
  TransferResult transfer(UUID from, UUID to, BigDecimal amount, TransferContext ctx);
  TransferResult transferBatch(List<TransferLeg> legs, TransferContext ctx);
}

public record TransferLeg(UUID from, UUID to, BigDecimal amount) {}
```

### TransferResult

戻り値は sealed の判別型。呼び出し側は `switch` で網羅的に扱う。

```java
public sealed interface TransferResult {
  record Success(
      long transferId,           // transaction_log の主キー
      Instant occurredAt,
      List<AppliedLeg> legs      // Modifier で追加された leg も含む
  ) implements TransferResult {}

  record InsufficientFunds(
      UUID account,
      BigDecimal available,
      BigDecimal required
  ) implements TransferResult {}

  record Vetoed(
      String modifierId,
      String reason
  ) implements TransferResult {}

  record AccountMissing(UUID which) implements TransferResult {}

  record InvalidAmount(BigDecimal amount, String reason) implements TransferResult {}  // 負・NaN・スケール超過など
}

public record AppliedLeg(
    UUID from,
    UUID to,
    BigDecimal amount,
    String legLabel   // Modifier が付けたラベル、または "primary"
) {}
```

### 呼び出し例

alias から UUID を解決するのは呼び出し側の責務。
System / Named 口座の UUID は alias から type-3 で決定的に導出できる（[02-account-model.md](./02-account-model.md)）ため、`AccountService.resolveAlias("system:job_pool")` か、事前に計算した定数を使う。

```java
AccountService accounts = Jecon.getService(AccountService.class);
TransferService svc     = Jecon.getService(TransferService.class);

UUID jobPool = accounts.resolveAlias("system:job_pool").orElseThrow();

TransferResult result = svc.transfer(
    jobPool,
    playerUuid,
    BigDecimal.valueOf(120),
    TransferContext.builder()
        .source("job")
        .metadata("job_id", "mining")
        .metadata("action_key", "break:diamond_ore")
        .build()
);

switch (result) {
  case TransferResult.Success s -> logger.info("paid, id={}", s.transferId());
  case TransferResult.InsufficientFunds f -> logger.warn("job_pool empty: {}", f.available());
  case TransferResult.Vetoed v -> logger.info("vetoed by {}: {}", v.modifierId(), v.reason());
  case TransferResult.AccountMissing m -> logger.error("missing: {}", m.which());
  case TransferResult.InvalidAmount ia -> throw new IllegalStateException(ia.reason());
}
```

## 複数 leg 振替（バッチ）

複数の振替を原子的に扱う。全 leg 成立か全 rollback。

```java
UUID taxSink = accounts.resolveAlias("system:tax_sink").orElseThrow();

TransferResult result = svc.transferBatch(
    List.of(
        new TransferLeg(buyer, shopOwner, itemPrice.multiply(BigDecimal.valueOf(0.9))),
        new TransferLeg(buyer, taxSink,   itemPrice.multiply(BigDecimal.valueOf(0.1)))
    ),
    TransferContext.builder()
        .source("shop")
        .metadata("shop_id", "downtown_iron_shop")
        .metadata("item_key", "minecraft:iron_ingot")
        .metadata("qty", "32")
        .build()
);
```

Modifier が追加 leg を生やす場合、それも同一トランザクションに含める（[05-modifier-pipeline.md](./05-modifier-pipeline.md)）。

`transferBatch` は独自 API 側にのみ露出する。
VaultUnlocked（[ADR-0011](./adr/0011-vaultunlocked-shared-account-no-async.md)）の `Economy` インタフェースは単一 leg の `transfer` しか持たないため、複数 leg の原子性を要求する呼び出し側は独自 API 経由で `TransferService.transferBatch(...)` を叩く必要がある。

## overdraft オプション

デフォルトでは残高不足時に `InsufficientFunds` を返す。
税徴収など、マイナス残高を許容してでも執行したいケースのために `withOverdraft()` を用意する（[ADR-0007](./adr/0007-allow-overdraft-optional.md)）。

```java
TransferContext ctx = TransferContext.builder()
    .source("tax")
    .withOverdraft()
    .build();
```

- overdraft が有効な leg は残高不足でも成功する。
  結果として残高が負になる。
- overdraft は `TransferContext` 全体に対して有効。
  Batch 内の一部 leg だけを overdraft、他は通常挙動、という混在は許さない。
- overdraft 経由でマイナスになった口座には、次回の deposit がまずマイナス埋めに使われる。
  Economy 本体は「マイナス残高からの復帰」を特別扱いしない（普通に足し算される）。

## 原子性の保証

`transfer` は Sync 一本（[07-persistence.md](./07-persistence.md)、[ADR-0012](./adr/0012-drop-lazy-repository.md)）で、常に 1 SQL トランザクションで完結する。

```sql
BEGIN;
SELECT balance FROM balance WHERE id = ? FOR UPDATE;    -- from の残高確認
SELECT balance FROM balance WHERE id = ? FOR UPDATE;    -- to のロック
UPDATE balance SET balance = balance - ? WHERE id = ?;  -- from
UPDATE balance SET balance = balance + ? WHERE id = ?;  -- to
INSERT INTO transaction_log (...) VALUES (...);
COMMIT;
```

UUID → `account.id` の解決は事前にキャッシュ引きで済ませ、`FOR UPDATE` は `account.id` 昇順で取ることでデッドロックを回避する。

複数 leg（`transferBatch`）は全 leg を同一トランザクションに含める。
関与する口座の `account.id` を昇順にソートして `FOR UPDATE` を取ってから、leg 順に `UPDATE` を発行する。

### 排他制御

`SELECT ... FOR UPDATE` はロックが強く、hot account（`system:vault_bridge` など）で競合しやすい。
競合が実測で問題になった場合、次の緩和策を検討する。

- 楽観ロック：`WHERE balance = ?` を条件に含め、失敗時にリトライ。
- 集計テーブルへの累積：`system:vault_bridge` は残高ではなく net flow だけを持つ「消滅口座」扱いにする（負の残高許容）。

Phase 1 では素直な `FOR UPDATE` で書き、実測してから最適化する。

## エラーモデル

`InsufficientFunds` と `AccountMissing` は正常系（呼び出し側が扱う）。
`InvalidAmount` はプログラミングエラー相当（負の金額、NaN、Big Decimal のスケール超過）で、通常呼び出し側で発生し得ない。
DB 層の例外（接続断など）は `TransferException`（RuntimeException）として上位に伝播させ、`TransferResult` の subtype にはしない。

## 関連 ADR

- [ADR-0004 transfer を一級 API にする](./adr/0004-transfer-as-primary-api.md)
- [ADR-0007 overdraft オプションを持たせる](./adr/0007-allow-overdraft-optional.md)
- [ADR-0012 LazyRepository を廃止し Sync 単一モードにする](./adr/0012-drop-lazy-repository.md)
- [ADR-0013 口座の主キーを UUID とし alias を副次表現とする](./adr/0013-uuid-primary-alias-secondary.md)
