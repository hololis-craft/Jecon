# ADR-0012 LazyRepository を廃止し Sync 単一モードにする

## ステータス

受け入れ（[ADR-0009](./0009-lazy-repository-transfer-semantics.md) を置き換える）

## 背景

Jecon 由来の `LazyRepository` は次の設計を持っていた。

- プレイヤーログイン時に DB から残高を読んで cache に載せる。
- 稼働中は cache 上で `deposit` / `withdraw` を反映する。
- ログアウト時 / shutdown 時、cache 値と初期ロード値の差分（delta）を DB に `UPDATE balance SET balance = balance + ?` として書き込む。

これは delta 書き込みなので別サーバ（BungeeCord 配下の別 Paper）で残高が変わっていても壊れず、multi-server safety を得られる仕組み。

しかし本仕様の `transfer` を導入した時点で、[ADR-0009](./0009-lazy-repository-transfer-semantics.md) では「transfer の際は cache 更新後、即時 DB delta 書き込みを 1 トランザクションで発行する」という併走モデルを採ることになった。
これは実質的に「Lazy の Lazy 部分」を短絡していて、以下の問題が残っていた。

- Lazy 経路と Sync 経路が混在し、cache と DB の整合を保つロジックが複雑化する。
- Named / System 口座には「ログイン / ログアウトで cache in/out する」自然なタイミングがなく、アイドル evict を別途持ち込む必要があった（[02-account-model.md](../02-account-model.md) 旧版）。
- クラッシュ耐性の観点でも「transfer は同期 DB 書き込み」に寄せざるを得ず、Lazy 由来の書き込み削減効果が hot path でほぼ効かない。
- 監査ログとの厳密な同期を保つには、どのみち残高更新と `transaction_log` INSERT を同一トランザクションに入れる必要があり、Lazy の恩恵は縮まっていた。

### 想定ワークロード

本イベントの想定は 100 名規模、single Paper もしくは 2〜3 Paper。
残高更新の頻度は 1 プレイヤーあたり数分に 1 回オーダーで、DB 直書きでも十分レイテンシは出る（`FOR UPDATE` のロック待ちが問題になるのは `system:vault_bridge` のような hot account のみ）。

複数 Paper 構成での multi-server safety は、`UPDATE balance SET balance = balance + ?`（絶対値ではなく delta）で書き込めば同等に確保できる。
Lazy の cache がなくても delta 書き込み方式は採用できる。

## 決定

Jecon 由来の `LazyRepository` を廃止し、Sync 単一モードに一本化する。

- 残高操作は常に DB を直接叩く。
- `getBalance(uuid)` は都度 SQL クエリ（`SELECT balance FROM balance WHERE id = ?`）を発行する。
  必要に応じて短寿命のスレッドローカルキャッシュ（数百 ms オーダー）を実装判断で採ることは許容するが、公開 API のセマンティクスには表れない。
- `transfer` は `UPDATE balance SET balance = balance ± ?` の delta 書き込みを 1 トランザクション内で行い、`transaction_log` INSERT も同トランザクションに含める。
- 複数 Paper 構成での安全性は、絶対値上書きではなく delta 書き込み（`balance = balance + ?`）で確保する。
- アイドル evict、cache resident、`resident_in_cache` 設定などの Lazy 由来概念はすべて撤去する。

### `BalanceRepository` の後方互換

[ADR-0010](./0010-backward-compat-balancerepository.md) の後方互換シムはそのまま維持する。
`BalanceRepository.deposit / withdraw / set` は `TransferService.transfer(...)` に転送し、Modifier pipeline と監査ログの恩恵を受ける。
Sync 化に伴う挙動変更は「cache を経由しなくなった」だけで、外部から見た API 契約（同期呼び出し、boolean 戻り値）は変わらない。

### transaction_log の書き込み

残高更新と同トランザクションで INSERT する。
Job プラグインの `action_log`（[Job spec 05-persistence.md](../../../Jobs/spec/05-persistence.md)）は 1 秒バッチだが、Economy の `transaction_log` は同期に留める（[04-context-and-log.md](../04-context-and-log.md)）。
理由：バッチにすると「残高は動いたが監査ログにない」瞬間ができ、クラッシュで監査が欠落する。

## 結果

- コードパスが一本化される（Lazy 経路と Sync 経路の分岐が消える）。
- Named / System 口座向けの「アイドル evict」ロジックが不要になる。
- クラッシュで失う transfer が理論上ゼロになる（DB commit で永続化）。
- multi-server safety は delta 書き込みで維持される。
- hot account（`system:vault_bridge`）の行ロック競合は実測して緩和策を採る。
  楽観ロックリトライや消滅口座扱いは [03-transfer-api.md](../03-transfer-api.md) の緩和策セクションで扱う。
- Jecon 由来の `LazyRepository` クラス / インタフェースは削除する。
  Sync 実装を `BalanceRepository` の唯一の実装として残す。
- [ADR-0009](./0009-lazy-repository-transfer-semantics.md) は本 ADR で置き換えとなる。

## 選択しなかった代替案

- **Lazy を残したまま transfer だけ Sync 化**：[ADR-0009](./0009-lazy-repository-transfer-semantics.md) の路線。Lazy の恩恵が hot path で効かないため、複雑さに見合わない。
- **Lazy を強化して transfer もキャッシュ経由に**：クラッシュ耐性と監査整合を犠牲にすることになる。本イベントの経済制御要件と合わない。
- **Sync 化 + 独自 short-lived cache**：実装判断としては採り得るが、公開 API のセマンティクスに乗せる必要はない。実装で判断する。

## 関連

- [ADR-0009 Lazy 下の transfer 意味論](./0009-lazy-repository-transfer-semantics.md)（置き換え元）
- [03-transfer-api.md](../03-transfer-api.md)
- [07-persistence.md](../07-persistence.md)
