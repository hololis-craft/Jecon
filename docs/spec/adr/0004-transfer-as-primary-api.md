# ADR-0004 transfer を一級 API にする

## ステータス

受け入れ

## 背景

Jecon の既存 API は `deposit(UUID, amount)` と `withdraw(UUID, amount)` の 2 本立てで、口座間の振替を「withdraw してから deposit」と呼び出し側が組む形になっている。

この構造には次の問題がある。

- **非原子**：`withdraw` が成功して `deposit` が失敗する（例：宛先口座が消滅済み）と、金が消える。
- **監査ログの断絶**：withdraw と deposit が別レコードで、片方だけ見ても何のための移動かわからない。
- **Modifier の書きにくさ**：pre-transfer フックを片側にだけかけるのは意味がなく、両側を組で見たい。
- **バッチ振替の表現不能**：Job の Splitter で n 分配、Shop の price + tax のように、1 つの取引から複数 leg が生まれるケースを 1 単位として扱えない。

Vault の `Economy` インタフェースも `depositPlayer` / `withdrawPlayer` の 2 本立てで、同じ制約を抱えている。

## 決定

`transfer(from, to, amount, ctx)` と `transferBatch(legs, ctx)` を Economy の一級 API とする。

- 単一 leg / 複数 leg いずれも、単一 DB トランザクションで実行する。
- 戻り値は sealed の `TransferResult`。呼び出し側は網羅的に扱う。
- 既存の `deposit` / `withdraw` は残す。内部実装は `transfer` を経由するシムに置き換える（[ADR-0010](./0010-backward-compat-balancerepository.md)）。
  片側の口座は `system:legacy_source` / `system:legacy_sink` にマップする。
- Vault 経由の呼び出しも同じシムを経由する（[ADR-0006](./0006-vault-through-modifier-pipeline.md)）。

原子性の実装：

- Sync 単一モード（[ADR-0012](./0012-drop-lazy-repository.md)）：`SELECT ... FOR UPDATE` + 2 本の UPDATE + INSERT を単一トランザクションで。
  ロック取得は `account.id` 昇順で。

## 結果

- 金の総量が壊れる余地が原理的に減る。
  複数 leg を含むトランザクションが原子的になる。
- Modifier pipeline を `transfer` に一本化でき、片側フックの矛盾を排除できる。
- Job プラグインの `Splitter`（1 アクション → 複数受取先）が自然に表現できる。
- 監査ログ `transaction_log` は `batch_id` で複数 leg を紐付けられ、事後分析で「この振替は何のためのものか」が復元できる。
- 既存 API の呼び出しは全て `transfer` に集約されるので、実装の重複が減る。
- 一方で、hot account（`system:vault_bridge`、`system:tax_sink`）にロックが集中する余地がある。
  Phase 1 は素直に `FOR UPDATE` で書き、実測してから最適化する（[03-transfer-api.md](../03-transfer-api.md)）。

## 選択しなかった代替案

- **`transfer` を用意せず `deposit` / `withdraw` の組み合わせで済ませる**：非原子と監査断絶の問題を放置することになり、cap_c 回路ブレーカーや税徴収のような要件と噛み合わない。
- **`transfer` を用意するが `deposit` / `withdraw` は独立に残す**：後方互換は担保できるが、Modifier pipeline や監査ログの source を新旧 API で二重管理することになる。シム化して統一する（[ADR-0010](./0010-backward-compat-balancerepository.md)）。

## 関連

- [03-transfer-api.md](../03-transfer-api.md)
- [ADR-0009 Lazy 下の transfer 意味論](./0009-lazy-repository-transfer-semantics.md)
- [ADR-0010 BalanceRepository の後方互換](./0010-backward-compat-balancerepository.md)
