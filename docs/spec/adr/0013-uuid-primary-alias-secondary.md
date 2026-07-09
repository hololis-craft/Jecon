# ADR-0013 口座の主キーを UUID とし alias を副次表現とする

## ステータス

受け入れ（[ADR-0002](./0002-account-ref-single-table.md) を置き換える）

## 背景

[ADR-0002](./0002-account-ref-single-table.md) では `AccountRef` sealed interface（`Player(UUID)` / `Named(namespace, key)` / `System(key)`）で口座を判別し、内部的には全口座に synthetic UUID を付けて共通テーブルに載せる構造を採っていた。
これには次の問題が残っていた。

- **公開 API の識別子が 2 種類ある**：Player は UUID、Named / System は `(namespace, key)` タプル。
  下流プラグインは `AccountRef` sealed のパターンマッチを書くか、`AccountRef.canonicalId()` の文字列を扱うかの選択を迫られる。
- **VaultUnlocked（`net.milkbowl.vault2.economy.Economy`）の shape との齟齬**：VaultUnlocked は全口座を UUID 一本で識別し、`String name` を副次的な表示名として扱う。
  本仕様が VaultUnlocked の shared account を非 Player 口座の実装として採用する（[ADR-0011](./0011-vaultunlocked-shared-account-no-async.md)）以上、API 表面も揃えたほうが写像が単純になる。
- **`namespace` 概念の露出**：Named / System の区別と namespace 文字列（`company`、`system`、`pool` 等）が API に出ることで、`AccountRef.Named` と `AccountRef.System` の使い分けが呼び出し側の判断になっていた。
  実態としては両者とも「非 Player 口座」であり、区別は運用命名の慣習にすぎない。

## 決定

口座は **UUID 主・alias 副** に統一する。

### Account

```java
public interface Account {
  UUID uuid();          // 主キー
  String alias();       // Player 名 or "<namespace>:<key>"
  boolean isPlayer();
  Instant createdAt();
}
```

- **UUID**：全口座の一意識別子。
  Player は Minecraft の UUID、非 Player は alias から派生する type-3 name-based UUID を割り当てる。
  外部プラグインはこの UUID で口座を指定する。
- **alias**：口座の副次表現。
  Player は Minecraft 名、非 Player は `<namespace>:<key>` の 1 文字列。
  検索・表示用途であり主キーではない。
- **isPlayer**：VaultUnlocked の `createAccount(uuid, name, boolean player)` の `player` フラグと同義。

`AccountRef` sealed interface と `AccountRef.Named` / `AccountRef.System` / `AccountRef.Player` の 3 分類は撤去する。

### alias の生成規則

- Player：Minecraft の player 名。
- 非 Player：`<namespace>:<key>`。
  - `namespace` は `[a-z0-9_-]{1,32}`、`key` は `[a-z0-9_-]{1,64}`、区切りは `:` 一文字。
  - 大文字小文字は区別しない（保存時に小文字化）。
  - 例：`system:tax_sink`、`company:acme`、`pool:event_2026`。

`namespace` は内部運用（`listByNamespace` 索引、DB `namespace` 派生列）で参照するが、公開 API 経由では alias 文字列としてのみ露出する（[ADR-0003](./0003-arbitrary-namespace-strings.md)）。

### UUID の派生

- Player：Minecraft の UUID。
- 非 Player：`UUID.nameUUIDFromBytes(alias.getBytes(UTF_8))`（type-3 name-based UUID）。
  alias が決まれば UUID も一意に決まる。
  外部プラグインも alias から自力で導出できるため、hot path でのリゾルバ呼び出しを避けられる。
- rename 時は UUID を変えない。
  Player の Minecraft 名変更、非 Player の運用命名変更が UUID 参照を壊さない。

### AccountService

VaultUnlocked の shape に揃える（[02-account-model.md](../02-account-model.md)、[06-public-api.md](../06-public-api.md)、[references/vault-unlocked-api.md](../../references/vault-unlocked-api.md)）。

```java
Account            createAccount(UUID uuid, String alias, boolean isPlayer);
Account            createSharedAccount(UUID uuid, String alias, UUID owner);
Optional<Account>  get(UUID uuid);
Optional<UUID>     resolveAlias(String alias);
boolean            rename(UUID uuid, String newAlias);
boolean            delete(UUID uuid);
List<Account>      listByNamespace(String namespace, int limit, int offset);
```

`resolveAlias` は運用コマンドと初期化用途で、hot path での多用は想定しない。

### DB スキーマ

`account` テーブルの主要列：

- `uuid BINARY(16) NOT NULL UNIQUE`（実質主キー）
- `alias VARCHAR(97) NOT NULL UNIQUE`
- `is_player TINYINT UNSIGNED NOT NULL`
- `namespace VARCHAR(32) NULL`（非 Player の alias から派生。索引用）
- `id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY`（内部 join 効率のための surrogate）

`transaction_log.from_id` / `to_id` は `account.id` を引き続き参照する（[07-persistence.md](../07-persistence.md)）。

## 結果

- 外部 API の口座指定は UUID に統一される。
  下流プラグイン（Shop、Stock、Job）は `AccountRef.Named(...)` のような型構文を書かず、UUID を扱う。
- 非 Player 口座の UUID は alias から type-3 で決定的に導出できるので、外部プラグインは `system:tax_sink` のような alias を hardcode する運用のまま自前で UUID を計算できる。
  Economy に事前問い合わせせずに口座を指定できる。
- VaultUnlocked（`net.milkbowl.vault2.economy.Economy`）との写像が 1:1 になる。
  `createAccount(uuid, name, boolean player)` と `createSharedAccount(uuid, name, owner)` を我々の `AccountService` がそのまま実装できる。
- alias UNIQUE 制約で二重命名を DB 側で防げる。
- `AccountRef` sealed interface の pattern match が不要になり、Player / 非 Player の分岐が必要な箇所は `AccountService.get(uuid).map(Account::isPlayer)` または `TransferProbe.isPlayer(uuid)` で行う。
- 下流の Shop / Stock spec に残る `AccountRef.Named(...)` などの型構文は書き換え必要。
  下流の書き換えは別タスクで扱う。
- [ADR-0002](./0002-account-ref-single-table.md) は本 ADR で置き換えとなる。

## 選択しなかった代替案

- **`(namespace, key)` タプルを公開 API に残す**：VaultUnlocked との写像で「タプル → UUID」変換を挟むことになり、外部プラグインが 2 種類の識別子を扱う負荷が減らない。
- **alias を主キーに、UUID を副次に**：VaultUnlocked が UUID 主キー shape のため、写像の向きが逆で不自然。
  また Player の Minecraft 名変更が主キー変更になり、外部参照を壊す。
- **`AccountRef` sealed を残したまま UUID を第一級識別子に**：sealed の存在意義（型で Player / Named / System を区別する）が消えるため、単に空の sealed を残すことになる。撤去する。

## 関連

- [ADR-0002 AccountRef を単一テーブルに載せる](./0002-account-ref-single-table.md)（置き換え元）
- [ADR-0003 namespace は任意文字列を許容する](./0003-arbitrary-namespace-strings.md)
- [ADR-0011 VaultUnlockedAPI 採用と shared account 写像](./0011-vaultunlocked-shared-account-no-async.md)
- [02-account-model.md](../02-account-model.md)
- [07-persistence.md](../07-persistence.md)
- [references/vault-unlocked-api.md](../../references/vault-unlocked-api.md)
