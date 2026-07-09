# 永続化

Jecon の既存スキーマ（`account`、`balance`、`transaction_log`）を拡張する。
方言別に SQL を分ける方針は Job プラグイン（[Job ADR-0018](../../Jobs/spec/adr/0018-repository-interface.md)）と同じ考え方で扱う。
LazyRepository は廃止し、Sync 単一モードで動かす（[ADR-0012](./adr/0012-drop-lazy-repository.md)）。

## Phase 1 バックエンド

MySQL を主とする。
Jecon が既に持つ SQLite 実装は保持し、開発用途と単一 Paper インスタンスの小規模運用向けに引き続き使えるようにする。
複数 Paper インスタンス構成では MySQL を要求する。

## テーブル一覧

| テーブル | 用途 |
|---|---|
| `account` | 口座（Player・非 Player 共通）の識別。UUID 主・alias 副 |
| `balance` | 残高 |
| `transaction_log` | 振替の監査ログ |

将来余地：`hourly_aggregate`、`namespace_meta`。
Phase 1 では作らない。

## account テーブル

UUID 主キー・alias 副次表現に統一する（[ADR-0013](./adr/0013-uuid-primary-alias-secondary.md)）。

```sql
CREATE TABLE account (
  id           INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  uuid         BINARY(16)      NOT NULL UNIQUE,
  alias        VARCHAR(97)     NOT NULL UNIQUE,
  is_player    TINYINT UNSIGNED NOT NULL,
  namespace    VARCHAR(32)     NULL,
  created_at   DATETIME(3)     NOT NULL,
  INDEX idx_namespace (namespace)
) ENGINE=InnoDB;
```

- `id` は内部 join 効率のために残す surrogate。`transaction_log.from_id` / `to_id` の外部キー参照に使う。
- `uuid` が実質的な主キー。
  Player は Minecraft の UUID、非 Player は `UUID.nameUUIDFromBytes(alias.getBytes(UTF_8))` による type-3 name-based UUID（[02-account-model.md](./02-account-model.md)）。
- `alias` は UNIQUE。
  Player は Minecraft 名、非 Player は `<namespace>:<key>`。
  最大長 97 = namespace 32 + `:` 1 + key 64。
- `is_player` は VaultUnlocked の `createAccount(uuid, name, boolean player)` の `player` フラグと同義。
- `namespace` は非 Player 口座で alias の `:` 前部分を派生保存する。
  `listByNamespace` の索引用途のみ。Player 口座では NULL。

Jecon の既存 `account` テーブルには `id`、`uuid`、`name` の 3 列しかない。
マイグレーションについては後述。

## balance テーブル

既存の Jecon のまま。

```sql
CREATE TABLE balance (
  id       INT UNSIGNED NOT NULL PRIMARY KEY,
  balance  BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
```

内部表現は cent 相当（`× 100`）の long。
オーバーフローは実質起きない（`Long.MAX_VALUE / 100 ≈ 92 京円`）。

## transaction_log テーブル

Jecon の既存 4 列版から拡張する。

```sql
CREATE TABLE transaction_log (
  id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  occurred_at    DATETIME(3) NOT NULL,
  source         VARCHAR(32) NOT NULL,
  from_id        INT UNSIGNED NULL,
  to_id          INT UNSIGNED NULL,
  amount         BIGINT NOT NULL,
  leg_label      VARCHAR(32) NOT NULL DEFAULT 'primary',
  batch_id       BIGINT UNSIGNED NULL,
  actor_uuid     BINARY(16) NULL,
  metadata       JSON NULL,
  INDEX idx_occurred (occurred_at),
  INDEX idx_source_time (source, occurred_at),
  INDEX idx_from_time (from_id, occurred_at DESC),
  INDEX idx_to_time (to_id, occurred_at DESC),
  INDEX idx_batch (batch_id)
) ENGINE=InnoDB;
```

`from_id` / `to_id` は `account.id`（内部 surrogate）を参照する。
UUID `BINARY(16)` を 2 列並べると索引効率が落ちるため、id 経由を選ぶ。
公開クエリは UUID で受け、内部で id にリゾルブしてから発行する。

既存の `type` 列は使わない。
マイグレーションでは新スキーマにコピーせず、既存レコードは古いテーブルにアーカイブとして残す（`transaction_log_v1` にリネーム）か、開発環境なら drop する。

## マイグレーションの流れ

Jecon の既存 `DBMigrationUtils` の枠組みに乗せる。
以下は MySQL 版の擬似 DDL。

```sql
-- v2: account を UUID 主・alias 副に整形
ALTER TABLE account
  CHANGE COLUMN name alias VARCHAR(97) NOT NULL,
  ADD COLUMN is_player   TINYINT UNSIGNED NOT NULL DEFAULT 1 AFTER alias,
  ADD COLUMN namespace   VARCHAR(32) NULL AFTER is_player,
  ADD COLUMN created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ADD UNIQUE KEY uk_alias (alias),
  ADD INDEX idx_namespace (namespace);

-- 既存レコードはすべて Player 口座扱い（is_player=1、namespace=NULL）
-- 非 Player 口座は必要になった時点で新規に createAccount / createSharedAccount する

-- v3: transaction_log を差し替え
RENAME TABLE transaction_log TO transaction_log_v1;
CREATE TABLE transaction_log ( ... );  -- 上記スキーマ
```

古いログ（`transaction_log_v1`）の変換は行わない。
既存 5 種類（`DEPOSIT/WITHDRAW/SET/CREATE/REMOVE`）は本仕様のドメイン理由と対応が取れないため、そのまま残して参考データとして扱う。

## Sync 単一モードでの動作

Jecon 由来の `LazyRepository`（delta 書き込みキャッシュ）は本仕様で廃止する（[ADR-0012](./adr/0012-drop-lazy-repository.md)）。
残高操作は常に DB を直接叩く同期実装 1 本にする。
複数 Paper インスタンス構成では、行ロック（`SELECT ... FOR UPDATE`）で並行制御する（[03-transfer-api.md](./03-transfer-api.md)）。

`transfer` の DB 書き込みは 1 トランザクションで `UPDATE balance` × 2 と `INSERT transaction_log` を発行する。
残高はキャッシュを持たず、`getBalance(uuid)` も DB へ都度クエリを発行する。
必要に応じて短寿命のスレッドローカルキャッシュ（数百 ms オーダー）を採ることは実装判断に委ねる。

`transaction_log` の INSERT は Job プラグインの `action_log`（[Job spec 05-persistence.md](../../Jobs/spec/05-persistence.md)）と同じ非同期バッチにする余地は残すが、Phase 1 では残高更新と同トランザクション（同期）で書く。
理由：バッチ非同期にすると「残高は動いたが監査ログにない」瞬間ができ、クラッシュで監査が欠落するため。

## SQLite 実装

Jecon は元々 SQLite と MySQL の両対応。
本仕様の拡張後も SQLite サポートは維持する。
`transaction_log.metadata` の JSON 列は、SQLite では `TEXT` として保存する。
functional index は貼れないため、`sumByMetadata` は table scan にフォールバックする（イベント終了時の分析クエリで許容）。

Job プラグイン側で SQLite を Phase 2 余地としているのに対し、Economy 側は Jecon 由来で Phase 1 から実装が存在するため、そのまま維持する。
複数 Paper インスタンス構成では SQLite は使えない。

## 保持期間

`transaction_log` は 90 日で削除する（[04-context-and-log.md](./04-context-and-log.md)）。
`config.yml` の `transaction_log.retention_days` で調整可。

`account` テーブルは削除しない。
Player 口座は Minecraft の UUID を保存するのに使うため。
非 Player 口座は明示的な `AccountService.delete(uuid)` でのみ削除する。

## 集計テーブル（将来）

業績指標クエリが頻発する場合、`hourly_aggregate` 相当のテーブルを追加する余地を残す。

```sql
CREATE TABLE hourly_aggregate (
  bucket_hour   DATETIME NOT NULL,
  source        VARCHAR(32) NOT NULL,
  from_id       INT UNSIGNED NULL,
  to_id         INT UNSIGNED NULL,
  total_amount  BIGINT NOT NULL,
  leg_count     INT NOT NULL,
  PRIMARY KEY (bucket_hour, source, from_id, to_id)
) ENGINE=InnoDB;
```

Phase 1 では作らず、`transaction_log` への直接クエリで運用する。
クエリ p95 が許容範囲を超えたら導入を検討する。

## 関連 ADR

- [ADR-0012 LazyRepository を廃止し Sync 単一モードにする](./adr/0012-drop-lazy-repository.md)
- [ADR-0013 口座の主キーを UUID とし alias を副次表現とする](./adr/0013-uuid-primary-alias-secondary.md)
- [ADR-0010 BalanceRepository の後方互換](./adr/0010-backward-compat-balancerepository.md)
