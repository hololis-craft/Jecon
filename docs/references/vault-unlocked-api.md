# VaultUnlocked API リファレンス

外部リンク：[TheNewEconomy/VaultUnlockedAPI](https://github.com/TheNewEconomy/VaultUnlockedAPI)（`net.milkbowl.vault2.economy` パッケージ）。

Economy プラグインが Named 口座と互換入口を実装するにあたり参照する外部 API の要点をまとめる。
一次資料は上記リポジトリの source（Economy.java 他）。
このドキュメントは spec 策定用の抜粋であり、細部は API のバージョン更新で変わりうる。

## 全体像

VaultUnlocked（Vault 2.x 後継）は旧 Vault（`net.milkbowl.vault.economy.Economy`）を口座 UUID 前提に置き換えたもの。
主な特徴：

- 全口座を `UUID accountID` で一級識別する。
  Player と非 Player の区別は `createAccount(uuid, name, boolean player)` の `player` フラグのみ。
- optional な `String name` を口座の表示名 / alias として持つ。
  ビジネスロジックではなく人間可読の副次情報。
- Shared account（共有口座）と `AccountPermission` enum。
  owner / member とフラグ単位の権限管理を API 標準で持つ。
- 多通貨（`String currency`）と world スコープ（`String worldName`）。
  未サポートの通貨は `NOT_IMPLEMENTED` を返す運用。
- 同期版 `Economy` に対して、非同期版 `AsyncEconomy` と CompletableFuture 版 `EconomyFutures` が併存する。
  `Economy#async()` から `Optional<AsyncEconomy>` を取れる（2.20+）。
- `pluginName` を多くのメソッドの第 1 引数に取る。
  Javadoc 上は logging / diagnostics only であり、business logic に影響してはならない、と明記されている。

## パッケージ構成

`net.milkbowl.vault2.economy` に以下の型が存在する。

| 型 | 役割 |
|---|---|
| `Economy` | 同期版のメインインタフェース |
| `AsyncEconomy` | 非同期版（`CompletableFuture` 戻り） |
| `EconomyFutures` | Async の Futures 変種 |
| `EconomyResponse` | 単一 leg の結果 |
| `MultiEconomyResponse` | 複数当事者の結果（transfer など） |
| `AccountPermission` | shared account 権限 enum |

## Economy インタフェース

### プラグイン情報

| メソッド | 戻り値 | 用途 |
|---|---|---|
| `isEnabled()` | `boolean` | プラグイン有効判定 |
| `getName()` | `String` | 実装プラグイン名 |
| `hasSharedAccountSupport()` | `boolean` | shared account 対応 |
| `hasMultiCurrencySupport()` | `boolean` | 多通貨対応 |
| `supportsAsync()` | `boolean` | async 対応（2.20+） |
| `async()` | `Optional<AsyncEconomy>` | async 実装取得（2.20+） |

### 通貨

| メソッド | 戻り値 | 用途 |
|---|---|---|
| `fractionalDigits(pluginName)` | `int` | 小数点以下桁数（デフォルト通貨）。未対応は `-1` |
| `fractionalDigits(pluginName, currency)` | `int` | 指定通貨の桁数 |
| `format(pluginName, amount)` | `String` | 人間可読フォーマット |
| `format(pluginName, amount, currency)` | `String` | 指定通貨でフォーマット |
| `hasCurrency(currency)` | `boolean` | 通貨存在判定 |
| `getDefaultCurrency(pluginName)` | `String` | 既定通貨名 |
| `defaultCurrencyNamePlural(pluginName)` | `String` | 既定通貨の複数形 |
| `defaultCurrencyNameSingular(pluginName)` | `String` | 既定通貨の単数形 |
| `currencies()` | `Collection<String>` | 利用可能な通貨一覧 |

`format(BigDecimal)` などの pluginName 無し版は 2.8 で deprecated。

### 口座作成・管理

| メソッド | 戻り値 | 用途 |
|---|---|---|
| `createAccount(uuid, name, player)` | `boolean` | 新規作成。`player=true` はプレイヤー口座 |
| `createAccount(uuid, name, worldName, player)` | `boolean` | world スコープ付き |
| `hasAccount(uuid)` | `boolean` | 存在判定 |
| `hasAccount(uuid, worldName)` | `boolean` | world 別存在判定 |
| `getAccountName(uuid)` | `Optional<String>` | 最終既知 name |
| `getUUIDNameMap()` | `Map<UUID, String>` | 全口座と最終既知 name |
| `renameAccount(uuid, name)` | `boolean` | name（alias）変更 |
| `renameAccount(pluginName, uuid, name)` | `boolean` | 同上、pluginName 明示 |
| `deleteAccount(pluginName, uuid)` | `boolean` | 削除 |

pluginName 無し版と world 引数の一部は 2.8 で deprecated。

### 残高照会

| メソッド | 戻り値 | 用途 |
|---|---|---|
| `balance(pluginName, uuid)` | `BigDecimal` | デフォルト world / 通貨の残高 |
| `balance(pluginName, uuid, world)` | `BigDecimal` | world 指定 |
| `balance(pluginName, uuid, world, currency)` | `BigDecimal` | world + 通貨指定 |
| `has(pluginName, uuid, amount)` | `boolean` | 十分残高か |
| `has(pluginName, uuid, world, amount)` | `boolean` | world 指定 |
| `has(pluginName, uuid, world, currency, amount)` | `boolean` | world + 通貨指定 |
| `accountSupportsCurrency(pluginName, uuid, currency)` | `boolean` | 通貨対応判定 |
| `accountSupportsCurrency(pluginName, uuid, currency, world)` | `boolean` | world + 通貨 |

`getBalance(...)` は 2.9 で deprecated（`balance(...)` に統一）。

### 残高変更

| メソッド | 戻り値 | 用途 |
|---|---|---|
| `deposit(pluginName, uuid, amount [, world][, currency])` | `EconomyResponse` | 入金 |
| `withdraw(pluginName, uuid, amount [, world][, currency])` | `EconomyResponse` | 出金 |
| `set(pluginName, uuid, amount [, world][, currency])` | `EconomyResponse` | 残高上書き |

### トランザクション事前判定

| メソッド | 戻り値 | 用途 |
|---|---|---|
| `canWithdraw(pluginName, uuid, amount [, world][, currency])` | `EconomyResponse` | 出金可否（2.19+） |
| `canDeposit(pluginName, uuid, amount [, world][, currency])` | `EconomyResponse` | 入金可否（2.19+） |

副作用なしで事前検証する経路。

### 振替

| メソッド | 戻り値 | 用途 |
|---|---|---|
| `transfer(pluginName, from, to, amount)` | `MultiEconomyResponse` | 2 口座間の振替 |
| `transfer(pluginName, from, to, world, amount)` | `MultiEconomyResponse` | world 指定 |
| `transfer(pluginName, from, to, world, currency, amount)` | `MultiEconomyResponse` | world + 通貨指定 |

VaultUnlocked の `transfer` は単一 leg のみ。
複数 leg の原子性は外部 API では表現しない。

### Shared account

| メソッド | 戻り値 | 用途 |
|---|---|---|
| `createSharedAccount(pluginName, uuid, name, owner)` | `boolean` | shared 口座作成 |
| `isAccountOwner(pluginName, uuid, target)` | `boolean` | owner 判定 |
| `setOwner(pluginName, uuid, newOwner)` | `boolean` | owner 変更 |
| `isAccountMember(pluginName, uuid, member)` | `boolean` | member 判定 |
| `addAccountMember(pluginName, uuid, member)` | `boolean` | member 追加 |
| `addAccountMember(pluginName, uuid, member, initialPerms...)` | `boolean` | 権限付きで追加 |
| `removeAccountMember(pluginName, uuid, member)` | `boolean` | member 削除 |
| `hasAccountPermission(pluginName, uuid, member, perm)` | `boolean` | 権限判定 |
| `updateAccountPermission(pluginName, uuid, member, perm, value)` | `boolean` | 権限更新 |

member 一覧 / owner 一覧 / 所属口座を UUID で取る API：

| メソッド | 戻り値 |
|---|---|
| `accountsWithOwnerOf(pluginName, uuid)` | `List<UUID>` |
| `accountsWithMembershipTo(pluginName, uuid)` | `List<UUID>` |
| `accountsWithAccessTo(pluginName, uuid, perms...)` | `List<UUID>` |

`accountsOwnedBy` / `accountsMemberOf` / `accountsAccessTo` は `List<String>` を返す旧版で deprecated。

## AccountPermission enum

`net.milkbowl.vault2.economy.AccountPermission`（`@since 2.7`）。

- `DEPOSIT`
- `WITHDRAW`
- `BALANCE`
- `TRANSFER_OWNERSHIP`
- `INVITE_MEMBER`
- `REMOVE_MEMBER`
- `CHANGE_MEMBER_PERMISSION`
- `OWNER`
- `DELETE`

個別の Javadoc は付いておらず、名前から用途を推測する。
Economy 側は enum の意味論には介入せず、値を保存・参照するだけの立場で扱う。

## EconomyResponse

単一 leg 結果を表す value class。

```java
public class EconomyResponse {
  public final BigDecimal amount;      // 実際に動かした額
  public final BigDecimal balance;     // 操作後の新残高
  public final ResponseType type;      // SUCCESS / FAILURE / NOT_IMPLEMENTED
  public final String errorMessage;    // type=FAILURE 時のメッセージ

  public boolean transactionSuccess(); // type == SUCCESS の便利判定
}
```

### ResponseType

| 値 | id | 用途 |
|---|---|---|
| `SUCCESS` | 1 | 成功 |
| `FAILURE` | 2 | 失敗（`errorMessage` に理由） |
| `NOT_IMPLEMENTED` | 3 | 実装なし（未対応通貨など） |

## MultiEconomyResponse

`transfer(...)` など複数当事者の結果。
各当事者ごとの `EconomyResponse` を集めた形。
（詳細は VaultUnlocked の source を都度確認する。）

## AsyncEconomy

Economy の非同期版。
全メソッドが `CompletableFuture<...>` を返す。

```java
CompletableFuture<Boolean>              createAccount(UUID id, String name, boolean player);
CompletableFuture<BigDecimal>           balance(String pluginName, UUID id);
CompletableFuture<EconomyResponse>      withdraw(String pluginName, UUID id, BigDecimal amount);
CompletableFuture<MultiEconomyResponse> transfer(String pluginName, UUID from, UUID to, BigDecimal amount);
// 同期版の全メソッドが CompletableFuture 版で提供される
```

`Economy#async()` が `Optional<AsyncEconomy>` を返す（2.20+）ため、呼び出し側は同期版 → `async().orElseThrow()` で切り替える。

## 呼び出し規約

### pluginName の扱い

- Javadoc に「logging / diagnostics only」「MUST NOT affect business logic」と明記。
- Economy 実装は監査ログの metadata に載せる用途に限定する。
- Modifier のディスパッチに使ってはならない。

### 世界 / 通貨引数の扱い

- `worldName` と `currency` を省略した overload は「サーバのデフォルト」を意味する。
- 未対応の通貨に対しては `ResponseType.NOT_IMPLEMENTED` を返すのが慣習。
- 本イベントは単一通貨 + world スコープ無しなので、渡された引数は無視するか `NOT_IMPLEMENTED` にフォールバックする。

### 名前（`String name`）の扱い

- `createAccount(uuid, name, ...)` の `name` は口座の alias / 表示名。
  Player 口座なら Minecraft 名、その他は用途に応じた任意文字列。
- 主キーではなく副次情報のため、`renameAccount` で変更できる。
- 検索の主経路は UUID。
  name からの逆引きは `getUUIDNameMap()` を舐める形になる。

## 本 spec との対応

| VaultUnlocked | 本 spec |
|---|---|
| `UUID accountID` | 全口座の一意識別子（主キー） |
| `String name`（alias） | Player なら Minecraft 名、System / Named なら namespaced 文字列 |
| `createAccount(uuid, name, player)` | 口座作成の唯一経路（内部 `AccountService` から委譲する） |
| `createSharedAccount(uuid, name, owner)` | 法人 / イベントプール等の shared 口座 |
| `AccountPermission` | shared 口座の権限。Economy は値を保存するだけ |
| `EconomyResponse` | 独自 `TransferResult` から必要に応じマップして返す |
| `AsyncEconomy` | Phase 1 では未実装（[ADR-0011](../spec/adr/0011-vaultunlocked-shared-account-no-async.md) 参照） |

具体的な写像方針は [08-vault-bridge.md](../spec/08-vault-bridge.md) と [ADR-0011](../spec/adr/0011-vaultunlocked-shared-account-no-async.md)。
