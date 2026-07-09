# ADR-0002 AccountRef を単一テーブルに載せる

## ステータス

置き換え（[ADR-0013](./0013-uuid-primary-alias-secondary.md) により `AccountRef` sealed interface を撤去し、UUID 主・alias 副の Account モデルへ移行した）

## 背景

Jecon の既存口座はプレイヤー UUID キー一本。
本イベントでは以下の非プレイヤー口座が必要になる。

- 法人口座（`company:<id>`）
- イベントプール（`pool:event_2026`）
- カジノハウス（`house:<id>`）
- 税収 sink（`tax_sink`）
- Vault ブリッジ対向（`vault_bridge`）

これらをテーブル上どう表現するかの選択肢が 3 つある。

### 案 A：`account` テーブルに `type` 列を足して同居させる

Jecon の `account(id, uuid BYTES)` を拡張し、`type` と `name` を足す。
UUID は Player の場合は Minecraft の UUID、Named/System は synthetic UUID を割り当てる。

### 案 B：非プレイヤー口座を別テーブル `named_account` に切る

`account`（プレイヤー用）と `named_account`（非プレイヤー用）を分離。
残高テーブルも `player_balance` と `named_balance` に分ける。

### 案 C：`account` テーブルに `type` を足し、`balance` テーブルは共通にする

案 A に近いが、UUID をキーにせず、`account.id` を全口座共通の内部 ID にする。

## 決定

案 A + C 折衷を採る。

- `account` テーブルに `ref_type`、`namespace`、`name_key`、`player_name` を足す。
- 全口座に synthetic UUID を割り当てる（Player は Minecraft UUID、Named/System は type-3 name-based UUID）。
- `balance` テーブルは既存構造のまま。`id` をキーにする。

外部 API では `AccountRef` sealed interface を使う。

```java
sealed interface AccountRef {
  record Player(UUID uuid) implements AccountRef {}
  record Named(String namespace, String key) implements AccountRef {}
  record System(String key) implements AccountRef {}
}
```

内部では `AccountRef` → `account.id` の解決を経由してから、`balance` テーブルを触る。
既存の Jecon のロジック（`getId(UUID)` → `id`、`balance` テーブルの `UPDATE`）はそのまま流用できる。

## 結果

- Named / System 口座も既存 Jecon の `LazyRepository` の cache と delta 書き込みロジックにそのまま乗る。
  クラスやテーブルを二重化しない分、コード量が増えない。
- `transfer` の SQL は from と to のテーブル分岐が不要で、単純な UPDATE 2 本 + INSERT で書ける。
- UNIQUE 制約 `(ref_type, namespace, name_key)` で二重作成を防げる。
- 一方で、Named / System 口座が Player と同じテーブルに載る分、テーブルは大きくなる。
  性能上の問題は想定していない（口座数の総和は 1000 も超えないため）。
- `ref_type` を都度チェックする必要が出るが、内部 ID に正規化してしまえば hot path での判定は要らない。

## 選択しなかった代替案

- **案 B（別テーブル）**：`transfer` の SQL に from/to のテーブル差分岐が入り、8 通りの書き分けが必要になる。原子的振替を書くときに Join 順序やロック順序が複雑化する。
- **UUID を持たない Named 口座**：Jecon の内部 API が UUID を前提とする箇所（`getId(UUID)`、`transaction_log.uuid` の既存列）を書き換える必要があり、コスト高。type-3 UUID を割り当てるだけで済む。

## 関連

- [02-account-model.md](../02-account-model.md)
- [07-persistence.md](../07-persistence.md)
