# 公開 API と Bukkit Event

外部プラグインが叩く経路と、購読できるイベントをまとめる。

## Service 取得

Jecon の既存パターン（`Jecon.getInstance().getRepository()`）に加え、Service Locator を用意する。

```java
public interface JeconServices {
  <T> T get(Class<T> serviceType);
}

Jecon jecon = (Jecon) Bukkit.getPluginManager().getPlugin("Jecon");
AccountService accounts = jecon.services().get(AccountService.class);
TransferService transfers = jecon.services().get(TransferService.class);
ModifierRegistry modifiers = jecon.services().get(ModifierRegistry.class);
TransactionQueryService queries = jecon.services().get(TransactionQueryService.class);
```

一覧：

| Service | 用途 |
|---|---|
| `BalanceRepository` | 既存 API。Jecon 由来の呼び出しをそのまま通す（[ADR-0010](./adr/0010-backward-compat-balancerepository.md)） |
| `AccountService` | 口座の生成、取得、削除、権限操作。[02-account-model.md](./02-account-model.md) |
| `TransferService` | 振替の実行。[03-transfer-api.md](./03-transfer-api.md) |
| `ModifierRegistry` | Modifier の登録・解除。[05-modifier-pipeline.md](./05-modifier-pipeline.md) |
| `TransactionQueryService` | 監査ログの集計クエリ。[04-context-and-log.md](./04-context-and-log.md) |

Bukkit の `ServicesManager` にも登録する（Vault ライクな呼び出しを許容するため）。

```java
Bukkit.getServicesManager().load(TransferService.class);
```

## AccountService

VaultUnlocked（`net.milkbowl.vault2.economy.Economy`）の shape に揃える（[ADR-0011](./adr/0011-vaultunlocked-shared-account-no-async.md)、[references/vault-unlocked-api.md](../references/vault-unlocked-api.md)）。
口座は UUID で扱う（[02-account-model.md](./02-account-model.md)、[ADR-0013](./adr/0013-uuid-primary-alias-secondary.md)）。

```java
public interface AccountService {
  Account            createAccount(UUID uuid, String alias, boolean isPlayer);
  Account            createSharedAccount(UUID uuid, String alias, UUID owner);
  Optional<Account>  get(UUID uuid);
  Optional<UUID>     resolveAlias(String alias);
  boolean            exists(UUID uuid);
  boolean            rename(UUID uuid, String newAlias);
  boolean            delete(UUID uuid);
  List<Account>      listByNamespace(String namespace, int limit, int offset);

  boolean   addMember(UUID account, UUID member, AccountPermission... initialPermissions);
  boolean   removeMember(UUID account, UUID member);
  boolean   setPermission(UUID account, UUID member, AccountPermission perm, boolean value);
  boolean   hasPermission(UUID account, UUID member, AccountPermission perm);
  Set<UUID> members(UUID account);
}
```

- `createAccount(uuid, alias, isPlayer)` は VaultUnlocked の `createAccount(uuid, name, player)` と等価。
  Player 口座は Minecraft の UUID + player 名で作る。
  非 Player 口座は `<namespace>:<key>` alias から派生した type-3 UUID + 同 alias で作る。
- `createSharedAccount` は VaultUnlocked の `createSharedAccount(uuid, name, owner)` と等価。
  owner を初期メンバとして全 `AccountPermission` 付きで登録する。
- `resolveAlias` は運用コマンドや外部プラグインの初期化時に使う。
  System / Named 口座なら UUID を alias から自力導出できるため、hot path では毎回 resolve する必要はない。

## Vault 系互換入口

外部プラグインとの互換入口として、以下の Vault 系 API を実装する（[08-vault-bridge.md](./08-vault-bridge.md)、[ADR-0011](./adr/0011-vaultunlocked-shared-account-no-async.md)）。

| 型 | 用途 |
|---|---|
| `net.milkbowl.vault.economy.Economy` | 旧 Vault 1.x 互換。EssentialsX 等が依存する既存デファクト。 |
| `net.milkbowl.vault2.economy.Economy` | VaultUnlocked（Vault 2.x 後継）。非 Player 口座と shared account を持つ。 |

両方を `Bukkit.getServicesManager()` に登録する。
呼び出し側はどちらを依存に取ってもよい。
内部実装はいずれも独自 `TransferService.transfer(...)` に委譲する（[ADR-0006](./adr/0006-vault-through-modifier-pipeline.md)）。

Phase 1 では `AsyncEconomy` と `EconomyFutures` は実装しない（[ADR-0011](./adr/0011-vaultunlocked-shared-account-no-async.md)）。
呼び出し側は同期版 `Economy` にフォールバックする。

## Bukkit Event

### JeconTransferCompletedEvent

振替が成立し、DB への UPDATE と監査ログ INSERT が発行された直後に発火する。
Cancellable ではない。

```java
public class JeconTransferCompletedEvent extends Event {
  public long getTransferId();
  public Instant getOccurredAt();
  public String getSource();
  public Map<String, String> getMetadata();
  public UUID getActor();               // nullable
  public List<AppliedLeg> getLegs();
}
```

メインスレッドで、**commit 順に、DB commit と同じ tick 以降**に発火する（[ADR-0014](./adr/0014-thread-safe-write-path.md)）。
振替は任意のスレッドから呼べるが、Bukkit は同期 event を非メインスレッドから発火できないため、
Jecon は event をキューに積んで毎 tick メインスレッドから流す。

そのため以下に注意する。

- **発火は `transfer()` の戻りより後**になる。呼び出し元のスレッドでは「振替完了 → event 購読側の処理完了」の順序を仮定できない。
- メインスレッドからの振替も同じキューを通る。commit 順と発火順を一致させるための意図的な設計で、同期呼び出しでも即時発火はしない。
- 発火が保証されるのは commit 成功時のみ。失敗（残高不足 / Veto / 口座なし）では発火しない。

副作用の重い処理（外部集計、通知）は購読側で async に飛ばす。

主な購読者：

- Stock プラグイン：業績指標クエリのバッファ更新。
- 統計プラグイン：時系列集計。
- 監査プラグイン：外部ログ収集。

### JeconAccountCreatedEvent

口座が新規生成されたときに発火する。
Player 口座の初回ログイン時、`AccountService.createAccount` / `createSharedAccount` 経由の作成時ともに発火する。

```java
public class JeconAccountCreatedEvent extends Event {
  public UUID getUuid();
  public String getAlias();
  public boolean isPlayer();
  public BigDecimal getInitialBalance();
}
```

イベントプールの初期化、法人口座の作成通知などに使う。

### JeconAccountRemovedEvent

口座削除時に発火する。
Player 口座は通常削除しないので、主に非 Player 口座の解散時に使う。

```java
public class JeconAccountRemovedEvent extends Event {
  public UUID getUuid();
  public String getAlias();
  public BigDecimal getFinalBalance();
}
```

## 既存 API との共存

`BalanceRepository` の従来メソッド（`deposit/withdraw/set/has/get`）は残す。
内部実装は `TransferService.transfer(...)` を呼ぶシムに置き換える（[ADR-0010](./adr/0010-backward-compat-balancerepository.md)）。
これにより既存プラグインは Modifier や監査ログの恩恵を無改造で受ける。

Vault 経由の `Economy.deposit/withdraw` も同じシムを経由する（[08-vault-bridge.md](./08-vault-bridge.md)）。

## パーミッション

新規に追加するコマンドと権限：

- `jecon.account.namespace.<namespace>.create`：指定 namespace の非 Player 口座を作成できる。
- `jecon.account.namespace.<namespace>.transfer`：指定 namespace の口座から / への振替をコマンド経由で発行できる。
- `jecon.transfer.overdraft`：`--overdraft` オプション付きの振替を発行できる。

コマンド `/jecon account create <alias> [initial]`、`/jecon account list <namespace>`、`/jecon account send <from-alias-or-uuid> <to-alias-or-uuid> <amount>` を追加する。
コマンドラインでは alias で受け、内部で UUID に解決してから `TransferService` を叩く。
既存 `/jecon give` `/jecon take` `/jecon pay` はそのまま維持し、内部で `TransferService` を呼ぶよう書き換える。

## スレッドモデル

Sync 単一モードなので Lazy 前提の注記は持たない（[ADR-0012](./adr/0012-drop-lazy-repository.md)）。

**公開 API は全て任意のスレッドから呼べる**（[ADR-0014](./adr/0014-thread-safe-write-path.md)）。
Vault の `Economy` は同期インタフェースであり、呼び出し元プラグインが async スレッドから
叩いてくることを Jecon 側では防げないため、そこを前提に組んでいる。

| Service | 呼び出し可能スレッド | 備考 |
|---|---|---|
| `AccountService.*` | 任意 | 書き込みは単一トランザクション。`account` 行のロックで直列化 |
| `TransferService.transfer` / `transferBatch` | 任意 | 単一トランザクション。`account.id` 昇順ロック |
| `TransferService.setBalance` | 任意 | 楽観的並行制御。競合上限で `TransferResult.Conflict` |
| `BalanceRepository.*` | 任意 | 書き込みは `TransferService` に委譲 |
| `ModifierRegistry.*` | 任意 | 実装は全メソッド `synchronized` |
| `TransactionQueryService.*` | 任意 | 重いクエリを想定。メインスレッドから呼ぶべきではない |

### 呼び出し側が守ること

- **同期 API は呼び出し元のスレッド上でそのまま実行される。** executor には投げないので、
  メインスレッドから呼べば DB の待ち時間がそのまま tick に乗る。並行する書き込みが
  行ロックを持っていれば、その解放も待つ。レイテンシを気にする場合は呼び出し側で
  async スレッドに逃がす。
- **メインスレッドから Jecon のワーカーを待つ構造を作らないこと。** Jecon 自身は
  「ワーカー → メイン」の待ち（Modifier pipeline の hop）だけを持つので循環しないが、
  呼び出し側が逆向きの待ちを作ると deadlock し得る。
- 一時的な競合（deadlock / `SQLITE_BUSY`）は Jecon 内部で再試行する。上限を超えた場合は
  `TransientDatabaseException` になる。データは変更されていない。

### TransferModifier

`TransferModifier.modify` は既定で**メインスレッドで実行される**。非メインスレッドからの
振替では、pipeline の実行だけをメインスレッドへ回す（1 tick 程度のレイテンシが乗る）。

`isThreadSafe()` に `true` を返すと hop を省略して呼び出し元スレッドで実行する。
`TransferProbe` と自身のスレッドセーフな状態しか触らない場合に限って宣言すること。
Bukkit API（`Player`、`World`、`Inventory`、scoreboard 等）に触るなら既定の `false` のままにする。

メインスレッドに回せなかった場合（停止処理中、5 秒のタイムアウト）、振替は
`TransferResult.Vetoed`（`modifierId` = `jecon:main-thread-bridge`）で失敗する。
modifier を飛ばして通すことはしない。

将来的に `TransferService.transferAsync`（`CompletableFuture` 版）を追加する余地は残す。
同期 API が任意のスレッドから安全になったので、これは「メインスレッドを待たせない」ための
利便性 API であり、正しさのために必要なものではない。

## 関連 ADR

- [ADR-0010 BalanceRepository の後方互換](./adr/0010-backward-compat-balancerepository.md)
- [ADR-0011 VaultUnlockedAPI 採用と shared account 写像](./adr/0011-vaultunlocked-shared-account-no-async.md)
- [ADR-0014 書き込み経路をスレッドセーフにする](./adr/0014-thread-safe-write-path.md)
- [ADR-0012 LazyRepository を廃止し Sync 単一モードにする](./adr/0012-drop-lazy-repository.md)
- [ADR-0013 口座の主キーを UUID とし alias を副次表現とする](./adr/0013-uuid-primary-alias-secondary.md)
