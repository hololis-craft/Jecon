# トランザクション context と監査ログ

Jecon の既存 `transaction_log` は `(timestamp, type, uuid, amount)` の 4 列で、type は `DEPOSIT/WITHDRAW/SET/CREATE/REMOVE` の 5 種類しかない。
ドメイン理由（`job`、`shop`、`tax`、など）は載らない。
本仕様では `TransferContext` を導入し、監査ログを拡張する（[ADR-0008](./adr/0008-transaction-context-source-and-metadata.md)）。

## TransferContext

```java
public record TransferContext(
    String source,                       // "job", "shop", "stock_dividend", "casino", "tax", "admin", "vault_bridge"
    Map<String, String> metadata,
    UUID actor,                          // command 発行者、null 可
    boolean overdraft
) {
  public static Builder builder() { ... }

  public interface Builder {
    Builder source(String s);
    Builder metadata(String k, String v);
    Builder metadata(Map<String, String> map);
    Builder actor(UUID uuid);
    Builder withOverdraft();
    TransferContext build();
  }
}
```

### source

呼び出し側が付ける短い識別子。
Economy 本体では特定の値を enum で固定せず、任意文字列を受け付ける。
慣習として次を推奨する。

- `job`：Job プラグインからの報酬支払い
- `shop`：Shop プラグインからの購入
- `stock_dividend`：Stock プラグインからの配当
- `casino`：カジノからの支払い / 徴収
- `tax`：税徴収
- `admin`：管理コマンド（`/jecon give` など）
- `vault_bridge`：Vault 経由の外部プラグイン呼び出し
- `legacy`：`BalanceRepository.deposit/withdraw` 直接呼び出し（後方互換経路）

Vault 経由の場合、`metadata` に `vault_caller` として呼び出し元プラグイン名を best-effort で入れる（[08-vault-bridge.md](./08-vault-bridge.md)）。

### metadata

自由文字列キー・値の Map。
サイズ制限は 1 レコードあたり合計 4KB（JSON エンコード後）。
超過分は打ち切って WARN ログを出す。
`transaction_log.metadata` は `JSON` 型（MySQL 5.7+）で保存する。

推奨キー（各 source ごとに慣習を揃える）：

- Job：`job_id`、`action_key`
- Shop：`shop_id`、`item_key`、`qty`
- Stock：`company_id`、`quarter`
- Casino：`game_id`、`round_id`
- Tax：`policy_id`
- Vault：`vault_caller`

### actor

コマンド発行者や、外部プラグインが人間の起点を追える場合の UUID。
Job や Stock のような自動発火では null。

### overdraft

`true` の場合、`InsufficientFunds` を返さず負残高を許容する（[03-transfer-api.md](./03-transfer-api.md)）。

## 監査ログのスキーマ

```sql
CREATE TABLE transaction_log (
  id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  occurred_at    DATETIME(3) NOT NULL,
  source         VARCHAR(32) NOT NULL,
  from_id        INT UNSIGNED NULL,        -- system:mint など片側が無い場合は NULL
  to_id          INT UNSIGNED NULL,        -- system:burn など
  amount         BIGINT NOT NULL,          -- 内部 long 表現（× 100）
  leg_label      VARCHAR(32) NOT NULL DEFAULT 'primary',
  batch_id       BIGINT UNSIGNED NULL,     -- 同一 batch の leg は共通 batch_id
  actor_uuid     BINARY(16) NULL,
  metadata       JSON NULL,
  INDEX idx_occurred (occurred_at),
  INDEX idx_source_time (source, occurred_at),
  INDEX idx_from_time (from_id, occurred_at DESC),
  INDEX idx_to_time (to_id, occurred_at DESC),
  INDEX idx_batch (batch_id)
) ENGINE=InnoDB;
```

- 単一 leg でも `batch_id` を持つ（同じ `id` を使う）。
  `transferBatch` の全 leg は同一 `batch_id` で結ばれる。
- Modifier が追加した leg は `leg_label` に Modifier ID または任意ラベルが入る。
  primary leg は `"primary"`。
- `from_id`、`to_id` は `account.id`（内部 surrogate）を参照する。
  UUID `BINARY(16)` を 2 列並べるより索引効率がよい（[07-persistence.md](./07-persistence.md)）。
  両方非 NULL のケースが通常。
  Jecon 由来の `deposit/withdraw` 直接呼び出しでは、片側が `system:legacy_source` / `system:legacy_sink` へマップされ非 NULL になる（[ADR-0010](./adr/0010-backward-compat-balancerepository.md)）。

## 書き込みのスレッドモデル

Job プラグインの `action_log` と同じ形（[Job spec 05-persistence.md](../../Jobs/spec/05-persistence.md)）にする。

- `transfer` の実行時、DB への `INSERT ... VALUES ...` は非同期キューに積む。
- 専用ワーカ 1 本がキューを drain し、100 件または 1 秒ごとに batch INSERT する。
- ただし残高更新（`UPDATE balance`）は同じトランザクション内で同期実行する。
  cache 更新も同期。

つまり、`transfer` 呼び出し完了時点で「金は動いたが監査ログはまだ書かれていない」瞬間が発生する。
クラッシュで最悪 1 秒分の監査ログが消える。
残高の整合性は cache と DB delta で保たれる（[03-transfer-api.md](./03-transfer-api.md)）。

監査ログを完全同期にする案もあるが、Job プラグインが高頻度に叩くため hot path を短く保ちたい。
Phase 1 は 1 秒 batch で始め、監査要件が厳しくなれば切り替える。

## 集計クエリ

外部プラグイン（株プラグイン、業績指標など）が監査ログを集計する経路は `TransactionQueryService` として提供する（[06-public-api.md](./06-public-api.md)）。
メインスレッドから叩かない前提。

```java
public interface TransactionQueryService {
  long countBySource(String source, TimeRange range);
  BigDecimal sumBySource(String source, TimeRange range);
  BigDecimal netFlow(UUID account, TimeRange range);
  List<TransactionRow> recent(UUID account, int limit);
  BigDecimal sumByMetadata(String source, String metadataKey, String metadataValue, TimeRange range);
}
```

`sumByMetadata` は JSON 列上の functional index を貼るか、`hourly_aggregate` 相当の集計テーブルを別途持つかで実装が分かれる。
Phase 1 は functional index で対応し、頻出クエリは後付けで集計テーブルを検討する（[07-persistence.md](./07-persistence.md)）。

## 保持期間

`transaction_log` は 90 日で削除する（`retention_days` 設定）。
イベント期間 21 日 + 事後分析 2 ヶ月を見込む。
削除は日次バッチで `DELETE ... WHERE occurred_at < ?` を発行する。
長期保管が必要な場合は外部のログ収集に流す。

## 関連 ADR

- [ADR-0008 transaction context に source と metadata を持たせる](./adr/0008-transaction-context-source-and-metadata.md)
- [ADR-0010 BalanceRepository の後方互換](./adr/0010-backward-compat-balancerepository.md)
