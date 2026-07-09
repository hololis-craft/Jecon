# 口座モデル

Jecon の口座はプレイヤー UUID のみをキーに取っていた。
本仕様では非プレイヤー口座を一級市民として扱いつつ、全口座を **UUID 主キー + `alias` 副次表現** の一枚岩で管理する（[ADR-0013](./adr/0013-uuid-primary-alias-secondary.md)）。
口座作成 API の shape は VaultUnlocked（`net.milkbowl.vault2.economy.Economy`）に揃える（[ADR-0011](./adr/0011-vaultunlocked-shared-account-no-async.md)、[references/vault-unlocked-api.md](../references/vault-unlocked-api.md)）。

## Account

```java
public interface Account {
  UUID uuid();          // 主キー
  String alias();       // Player なら Minecraft 名、非 Player なら "<namespace>:<key>"
  boolean isPlayer();
  Instant createdAt();
}
```

- **UUID**：全口座の一意識別子。
  Player は Minecraft の UUID、非 Player は alias から派生する type-3 name-based UUID を割り当てる（後述）。
  外部プラグインはこの UUID で口座を指定する。
- **alias**：口座の副次表現。
  人間可読の名前であり、Player の場合は Minecraft 名、非 Player の場合は namespaced な文字列を用いる。
  検索・表示用途であって主キーではない。
  Player の rename 追随や System 口座の運用ドキュメントで役立つ。
- **isPlayer**：VaultUnlocked の `createAccount(uuid, name, boolean player)` の `player` フラグと同じ意味。
  Player 口座か非 Player 口座かを区別する。

`AccountRef` sealed interface（Player / Named / System）は撤去する。
Player / Named / System の区別が必要な箇所は `isPlayer` と alias の namespace 部で判定する。

## alias の表記規約

alias は次のいずれかの形をとる。

- **Player**：Minecraft の player 名。
  例：`f0reach`。
  Minecraft 側で rename されたら `AccountService.rename(uuid, newName)` で追随する。
- **非 Player**：`<namespace>:<key>` の 1 文字列。
  例：`system:tax_sink`、`company:acme`、`pool:event_2026`、`house:casino_main`。

`namespace` と `key` はそれぞれ次の形を要求する。

- 使用文字：`[a-z0-9_-]`
- 長さ：`namespace` は 1〜32 文字、`key` は 1〜64 文字
- 大文字小文字は区別しない（保存時に小文字化する）
- 区切り記号は `:` 一文字のみ

規約違反は `IllegalArgumentException`。

`namespace` は内部運用（DB 索引、`listByNamespace` 運用コマンド）で参照するが、公開 API では alias の文字列としてのみ露出する（[ADR-0003](./adr/0003-arbitrary-namespace-strings.md)）。

### 用途別の慣習

非 Player 口座の namespace 慣習。
Economy 本体では enum で固定せず、呼び出し側が命名する。

- `system:*`：Economy プラグイン内部の対向口座（`system:vault_bridge`、`system:tax_sink`、`system:mint`、`system:burn` など）
- `company:*`：法人口座
- `pool:*`：イベント全体の報酬プール
- `house:*`：カジノハウス
- `guild:*`：ギルド金庫（将来）

`system:tax_sink` / `system:mint` / `system:burn` は Economy 本体では作らない。
必要になった時点で外部プラグインが `createAccount` する。

## UUID の派生

- **Player**：Minecraft の UUID をそのまま採用。
- **非 Player**：`UUID.nameUUIDFromBytes(alias.getBytes(UTF_8))` によるバージョン 3（name-based）UUID。
  alias が決まれば UUID も一意に決まる。
  外部プラグインも alias から自力で UUID を導出できるので、Economy に問い合わせなくても口座指定が可能。

alias が変わっても UUID は変えない（rename しても UUID は不変）。
これにより Player 名変更や運用命名の変更が UUID 参照を壊さない。

## AccountService

口座生成と検索の公開 API。
VaultUnlocked の shape を踏襲する（[ADR-0011](./adr/0011-vaultunlocked-shared-account-no-async.md)、[references/vault-unlocked-api.md](../references/vault-unlocked-api.md)）。

```java
public interface AccountService {
  // 口座生成
  Account createAccount(UUID uuid, String alias, boolean isPlayer);
  Account createSharedAccount(UUID uuid, String alias, UUID owner);

  // 取得・解決
  Optional<Account> get(UUID uuid);
  Optional<UUID>    resolveAlias(String alias);
  boolean           exists(UUID uuid);

  // ライフサイクル
  boolean rename(UUID uuid, String newAlias);
  boolean delete(UUID uuid);

  // 内部運用向け
  List<Account> listByNamespace(String namespace, int limit, int offset);

  // 権限（VaultUnlocked の AccountPermission と等価）
  boolean   addMember(UUID account, UUID member, AccountPermission... initialPermissions);
  boolean   removeMember(UUID account, UUID member);
  boolean   setPermission(UUID account, UUID member, AccountPermission perm, boolean value);
  boolean   hasPermission(UUID account, UUID member, AccountPermission perm);
  Set<UUID> members(UUID account);
}
```

`AccountService` は `Jecon.getService(AccountService.class)` で取得する（[06-public-api.md](./06-public-api.md)）。

### createAccount

VaultUnlocked の `createAccount(uuid, name, boolean player)` と等価。
`isPlayer=true` なら Player 口座、`false` なら非 Player 口座を作る。
非 Player の場合、alias は必ず `<namespace>:<key>` 形式でなければならない。

プレイヤー口座は既存の Jecon 起動フロー（初回ログイン時作成）に従うが、`createAccount` 経由で明示作成することもできる。

### createSharedAccount

VaultUnlocked の `createSharedAccount(uuid, name, owner)` と等価。
非 Player 口座を作り、`owner` を初期メンバとして全 `AccountPermission` 付きで登録する。
法人口座、イベントプールなど、owner / member 権限管理が必要な口座はこちらで作る。

### resolveAlias

alias 文字列から UUID を引く。
Player の Minecraft 名からの逆引きに使う場合、name change を追跡していない外部システムでは古い UUID を得る可能性があるので、hot path 用途は `get(UUID)` を優先する。

### listByNamespace

`namespace` プレフィクスに合致する alias を持つ非 Player 口座を列挙する。
運用コマンド（`/jecon account list system`）や管理 UI から使う想定。
外部プラグインからの hot-path アクセスは推奨しない。

## 権限（AccountPermission）

VaultUnlocked の `AccountPermission` enum に沿う（[references/vault-unlocked-api.md](../references/vault-unlocked-api.md)）。
Economy は enum の意味論に介入せず、値を保存・参照するだけ。

- `DEPOSIT`
- `WITHDRAW`
- `BALANCE`
- `TRANSFER_OWNERSHIP`
- `INVITE_MEMBER`
- `REMOVE_MEMBER`
- `CHANGE_MEMBER_PERMISSION`
- `OWNER`
- `DELETE`

法人口座の「社長」「役員」「社員」といったロールの semantic は Company / Event 固有プラグインが決める。
Economy が持つのは「この口座に対してこの UUID がどの permission を持つか」だけ。

`TransferModifier` からは `TransferProbe.hasPermission(account, member, perm)` で参照できる（[05-modifier-pipeline.md](./05-modifier-pipeline.md)）。
ただし permission チェックは呼び出し側（Company / Shop プラグイン等）の責務が原則で、Modifier は経済ポリシー（税、cap、日次キャップ）に専念する慣習を推奨する。

## テーブルスキーマ

詳細は [07-persistence.md](./07-persistence.md)。

```sql
CREATE TABLE account (
  id           INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  uuid         BINARY(16)      NOT NULL UNIQUE,
  alias        VARCHAR(97)     NOT NULL UNIQUE,       -- Player 名 or "<namespace>:<key>" (最大 32+1+64)
  is_player    TINYINT UNSIGNED NOT NULL,
  namespace    VARCHAR(32)     NULL,                   -- 非 Player のみ。alias から派生する副次列
  created_at   DATETIME(3)     NOT NULL,
  INDEX idx_namespace (namespace)
) ENGINE=InnoDB;
```

- `uuid` は主キーの役割を担う一意列（`id` は内部 join 効率のために残す）。
- `alias` は UNIQUE。
  重複命名を DB 側で防ぐ。
- `is_player` は Player / 非 Player の区別。
- `namespace` は非 Player 口座で、alias の `:` 前部分を派生保存する。
  `listByNamespace` の索引用途。

## VaultUnlocked shared account との対応

VaultUnlocked（`net.milkbowl.vault2.economy.Economy`）を Economy 本体が実装する（[ADR-0011](./adr/0011-vaultunlocked-shared-account-no-async.md)、[08-vault-bridge.md](./08-vault-bridge.md)）。
VaultUnlocked API 経由の呼び出しと Economy 独自 API 経由の呼び出しは同じ内部テーブルを操作する。

| 我々の概念 | VaultUnlocked での表現 |
|---|---|
| `Account`（`isPlayer=true`） | `createAccount(uuid, name, player=true)` |
| `Account`（`isPlayer=false`） | `createAccount(uuid, name, player=false)` |
| `Account`（shared / 権限付き） | `createSharedAccount(uuid, name, owner)` |
| `AccountService.rename(uuid, alias)` | `renameAccount(uuid, name)` |
| `AccountService.addMember(...)` 等 | shared account の `addAccountMember` 等 |

`AccountPermission` の enum 値も VaultUnlocked のそれを直接使う。

## 関連 ADR

- [ADR-0002 AccountRef を単一テーブルに載せる](./adr/0002-account-ref-single-table.md)（置き換え済）
- [ADR-0003 namespace は任意文字列を許容する](./adr/0003-arbitrary-namespace-strings.md)
- [ADR-0011 VaultUnlockedAPI 採用と shared account 写像](./adr/0011-vaultunlocked-shared-account-no-async.md)
- [ADR-0013 口座の主キーを UUID とし alias を副次表現とする](./adr/0013-uuid-primary-alias-secondary.md)
