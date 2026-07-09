# ADR-0008 transaction context に source と metadata を持たせる

## ステータス

受け入れ

## 背景

Jecon の既存 `transaction_log` は `(timestamp, type, uuid, amount)` の 4 列で、type は `DEPOSIT/WITHDRAW/SET/CREATE/REMOVE` の 5 種類のみ。
ドメイン理由（Job の報酬なのか、Shop の支払いなのか、税なのか）は載らない。

本イベントで必要な集計クエリの例：

- Job プラグインが払った報酬の合計（1 時間あたり、プレイヤー別）
- Shop 経由の売上合計（1 日あたり、Shop 別）
- 税収の総額（累計）
- ある企業口座の入出金明細
- Vault 経由で外部プラグインが払った額（プラグイン別、best-effort）

これらを既存の 4 列テーブルから引くのは不可能。

### 案 A：source 列を追加するだけ

`transaction_log` に `source VARCHAR(32)` を足す。
Job / Shop / Tax 単位の集計ができるようになる。

限界：ドメイン特有の粒度（`job_id`、`shop_id`、`action_key`）で集計できない。

### 案 B：source + metadata の JSON 列

`source VARCHAR(32)` + `metadata JSON`。
metadata は呼び出し側が自由に載せる。

利点：どんな粒度でも後から集計できる。
欠点：JSON 列のクエリ性能、スキーマレスゆえのタイポリスク。

### 案 C：source + 固定カラムをドメインごとに追加

`source` に加えて `job_id`、`shop_id`、`action_key` など固定カラムを増やす。

利点：型安全、インデックス化しやすい。
欠点：Economy 本体がドメイン特有の列を知る必要がある（`shop_id` は Shop の概念）。Economy の汎用性を損なう。

## 決定

案 B を採る。`source VARCHAR(32)` と `metadata JSON`。

- `source` は短い識別子（`job`、`shop`、`tax`、`vault_bridge` など）。
  Economy 本体は特定値を知らない。慣習は仕様書に列挙する（[04-context-and-log.md](../04-context-and-log.md)）。
- `metadata` は自由 Map。呼び出し側が構造化キーを載せる。
  推奨キーは source ごとに慣習を揃える。
- MySQL の JSON 型を使う。
- SQLite は TEXT で JSON をシリアライズして保存。集計クエリは table scan にフォールバック。

サイズ制限：metadata は JSON エンコード後 4KB まで。
超過は打ち切って WARN ログ。

集計性能：

- 頻出クエリ（`sumBySource`、`netFlow`）は既存インデックス（`(source, occurred_at)`、`(from_id, occurred_at)`）で対応。
- `sumByMetadata` は JSON functional index（MySQL 5.7+）で個別対応。
- クエリ p95 が悪化したら `hourly_aggregate` 集計テーブルを追加する（[07-persistence.md](../07-persistence.md)）。

## 結果

- 呼び出し側が「どの粒度で集計したいか」を将来の変更込みで自由に決められる。
- Economy 本体は各ドメイン（Job、Shop、Stock）の内部構造を知らない汎用基盤に留まる。
- 集計クエリを外部プラグイン（株プラグイン、業績指標プラグイン）から発行する経路は `TransactionQueryService` として提供する（[06-public-api.md](../06-public-api.md)）。
- スキーマレスゆえのタイポリスクは残る。
  各プラグインが `metadata` を書き込むときはヘルパを経由することを推奨する（`ShopTransferContext.builder().shopId(...).build()` のように）。
- JSON 列のクエリコストは、`source` インデックスで絞り込んだ後の filter として発行する分には問題ない見込み。

## 選択しなかった代替案

- **案 A（source のみ）**：粒度が粗すぎて `job_id` 別集計ができない。事後分析のニーズを満たせない。
- **案 C（ドメイン列を Economy に足す）**：Economy 本体が Job / Shop の概念を知ってしまい、循環依存を招く。プラグイン境界が曖昧になる。

## VaultUnlocked の pluginName 引数との関係

VaultUnlocked（[ADR-0011](./0011-vaultunlocked-shared-account-no-async.md)）の各メソッドは `String pluginName` を第 1 引数に取る。
Javadoc に「logging/diagnostics only, MUST NOT affect business logic」と明記されており、Modifier のディスパッチには使えない。

我々の扱い：

- VaultUnlocked 経由の呼び出しでは、`pluginName` を `metadata["plugin_name"]` と `metadata["vault_caller"]` の両方に入れる。
- Modifier で経路を分岐する場合は `source().equals("vault_unlocked")` を見る。
  Modifier で `pluginName` を条件に使うのは Javadoc の建前に反するため、慣習として避ける。
- 監査ログの事後分析では `metadata["plugin_name"]` を参照して「どの外部プラグイン経由の振替か」を追跡できる。

旧 Vault 経由の呼び出しでは、`pluginName` は API から渡らないため `StackWalker` 経由の best-effort 推定を `metadata["vault_caller"]` に入れる（[08-vault-bridge.md](../08-vault-bridge.md)）。

## 関連

- [04-context-and-log.md](../04-context-and-log.md)
- [07-persistence.md](../07-persistence.md)
- [ADR-0011 VaultUnlockedAPI 採用](./0011-vaultunlocked-shared-account-no-async.md)
