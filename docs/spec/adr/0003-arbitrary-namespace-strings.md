# ADR-0003 namespace は任意文字列を許容する

## ステータス

受け入れ

## 背景

`AccountRef.Named` の namespace の値域として、以下の候補があった。

### 案 A：enum で固定

Economy 側で `COMPANY`、`POOL`、`HOUSE`、`TAX`、`GUILD` などの enum を持ち、それ以外を拒否する。

利点：

- タイポ耐性が高い。
- IDE 補完が効く。
- Modifier で `namespace == COMPANY` の分岐が型安全。

欠点：

- Economy 本体が知らない用途（別プラグインが独自の名前空間を使いたい）に対応できない。
- 新しい namespace を足すたびに Economy 本体のリリースが要る。
- 「事業立ち上げ」のようなイベント特有の namespace が Economy 本体に流入する。

### 案 B：任意文字列

`namespace` は自由文字列。命名規約（`[a-z0-9_-]`、長さ制限）だけを Economy 側で強制する。

利点：

- 新しい用途を Economy 本体の変更なしに追加できる。
- Economy 本体は「口座名の keyspace を管理する」だけの汎用基盤に留まる。

欠点：

- タイポ検出は呼び出し側の責任。
- Modifier で namespace 単位の分岐を書くとき、文字列比較になる。
- パーミッションキー（`jecon.account.namespace.<ns>.create`）が動的になる。

## 決定

案 B を採る。namespace は任意文字列。

命名規約：

- 使用文字：`[a-z0-9_-]`
- 長さ：`namespace` は 1〜32 文字、`key` は 1〜64 文字
- 大文字小文字は区別しない（保存時に小文字化）

Economy 本体は namespace の意味を知らない。
慣習として推奨する namespace は仕様書に列挙する（[02-account-model.md](../02-account-model.md)）が、強制はしない。

## 結果

- 新プラグイン（例：ギルド、フリマ、副業組合）が独自の namespace を使える。
  Economy 本体は変更なし。
- タイポ耐性は各プラグイン側で `"company:" + id` を組み立てるヘルパや、alias 定数を持つことで担保する。
- パーミッションキーが動的になるが、Bukkit の permission system はワイルドカード（`jecon.account.namespace.*.transfer`）を許容するため運用は成立する。
- Modifier で namespace 単位の分岐を書くときは文字列比較になる。
  alias（`"<namespace>:<key>"`）の `:` 前部分を抜き出すか、内部で保持している `namespace` 派生列を参照する。

## 射程（後述の更新）

本 ADR で決めた「namespace は任意文字列」の方針はそのまま維持する。
ただし [ADR-0013](./0013-uuid-primary-alias-secondary.md) により、**namespace は公開 API のパラメータとしては露出せず、alias 文字列の一部（`<namespace>:<key>`）と、内部の索引用列としてのみ現れる** ことになった。

- 外部プラグインが口座を指定するのは UUID。alias を組み立てるときにだけ namespace 文字列を書く。
- Modifier からは `TransferProbe.alias(uuid)` の返り値をパースするか、事前計算した alias 定数を参照する。
- 内部の DB 列 `account.namespace` は `listByNamespace` 索引の用途のみ。

## 選択しなかった代替案

- **案 A（enum）**：Economy 本体が特定の用途を知る必要があるが、本イベントで想定する範疇を超えて namespace が増える見込みがあり、汎用性を残したい。
- **enum + arbitrary の混在**（Economy 側に built-in namespace enum、それに加えて `Other(String)`）：型安全と柔軟性の中間を狙うが、判定ロジックが二重になり複雑度が増す。純粋な文字列で十分。

## VaultUnlocked との整合

VaultUnlocked（[ADR-0011](./0011-vaultunlocked-shared-account-no-async.md)）の口座は `UUID + optional name` のみで、namespace 概念を持たない。
本 ADR で決めた任意 namespace は、[ADR-0013](./0013-uuid-primary-alias-secondary.md) 以降は alias 文字列（`<namespace>:<key>`）の内部規約として扱う。

- 非 Player 口座の name（VaultUnlocked では `String name`、Economy 内部では `alias`）は `<namespace>:<key>` 形式。
- VaultUnlocked API 経由で口座を参照する外部プラグインには、`UUID + name` として見える（VaultUnlocked の建前を破らない）。
- 独自 API（`AccountService`、`TransferService`）を経由する呼び出しでも公開パラメータは UUID + alias 文字列。
  `(namespace, key)` タプルは受け取らない。
- Modifier からは `TransferProbe.alias(uuid)` の返り値の `:` 前部分で namespace を判定できる。
- DB の `account.namespace` 派生列は `listByNamespace` 索引の用途のみ。

外部プラグインが namespace の意味を知る必要がある場合（例：法人と個人事業主を分ける Modifier）は、alias の `:` 前部分を参照するか、`resolveAlias("company:...")` 経由で UUID を得る。

## 関連

- [02-account-model.md](../02-account-model.md)
- [ADR-0011 VaultUnlockedAPI 採用](./0011-vaultunlocked-shared-account-no-async.md)
- [ADR-0013 口座の主キーを UUID とし alias を副次表現とする](./0013-uuid-primary-alias-secondary.md)
