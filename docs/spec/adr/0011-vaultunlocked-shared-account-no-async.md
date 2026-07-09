# ADR-0011 VaultUnlockedAPI を採用し、shared account を Named 口座に写像、Async は実装しない

## ステータス

受け入れ

## 背景

外部プラグイン（EssentialsX、SellChest、その他）との経済 API 互換の入口として、旧 Vault（`net.milkbowl.vault.economy.Economy`、Vault 1.x）に加えて後継の [VaultUnlockedAPI](https://github.com/TheNewEconomy/VaultUnlockedAPI)（`net.milkbowl.vault2.economy.Economy`、以下 VaultUnlocked）を検討した。

VaultUnlocked の主な追加機能：

- 全口座が `UUID accountID` + optional `String name`。`createAccount(uuid, name, boolean player)` の `player` フラグで区別。
- 多通貨（`String currency` 引数）と world スコープ。
- 単一 leg の `transfer(pluginName, from, to, amount [, world][, currency])`。
- **Shared account + `AccountPermission` enum**：`createSharedAccount(uuid, name, owner)` と member 追加、権限管理 API。owner/member のロールと DEPOSIT/WITHDRAW/BALANCE/TRANSFER_OWNERSHIP/INVITE_MEMBER などのフラグを持つ。
- `AsyncEconomy` と `EconomyFutures`（CompletableFuture 版）が別インタフェースで存在。
- `pluginName` 引数は Javadoc に「logging/diagnostics only, MUST NOT affect business logic」と明記。

VaultUnlocked に載らない本 spec の要件は、[Job プラグイン設計](../../../Jobs/spec/)と同様の理由で外せない。

- Batch transfer の原子性（Splitter、税分離）
- `TransferContext`（source、metadata、actor）
- `TransferModifier` パイプライン
- Post-transfer Bukkit event
- 監査ログ集計 API

つまり VaultUnlocked を採用しても、我々の独自 SPI（[03-transfer-api.md](../03-transfer-api.md)、[05-modifier-pipeline.md](../05-modifier-pipeline.md)、[06-public-api.md](../06-public-api.md)）は残る。
選択できるのは「独自 SPI と Vault / VaultUnlocked をどう共存させるか」の形。

一方、VaultUnlocked の shared account + `AccountPermission` は本 spec の Named 口座（法人、イベントプール）と親和性が高い。
特に法人口座の「社長」「役員」「社員」といったロールを Economy 側で保持できる。
これを独自実装すると同じテーブル・API を二重管理することになる。

Async は VaultUnlocked が持つ機能だが、本 spec の `TransferService` はメインスレッド同期実行を前提としている（[06-public-api.md](../06-public-api.md)）。
Async を提供する場合、独自 SPI 側にも `TransferServiceAsync` を並置する必要が出る。
本イベントの想定ワークロード（100 名規模）では非同期化の必要はなく、Async を実装しない選択が妥当。

## 決定

以下の 4 点を採る。

### 1. VaultUnlocked を Economy プラグイン本体が実装する

`net.milkbowl.vault2.economy.Economy` を Jecon fork 本体が実装し、`ServicesManager` に登録する。
旧 Vault（`net.milkbowl.vault.economy.Economy`）も引き続き実装する（[08-vault-bridge.md](../08-vault-bridge.md)）。

VaultUnlocked 経由の呼び出しは、内部で独自 `TransferService.transfer(...)` に委譲する。

- `TransferContext.source = "vault_unlocked"` を設定する。
- `TransferContext.metadata["plugin_name"]` に VaultUnlocked 側から渡された `pluginName` を入れる（Javadoc の logging-only 建前を守る）。
- Modifier pipeline を通す（[ADR-0006](./0006-vault-through-modifier-pipeline.md)）。

### 2. 口座作成 API を VaultUnlocked の shape で揃える

独自 `AccountService` の口座作成 API を VaultUnlocked と同じ shape にする（[ADR-0013](./0013-uuid-primary-alias-secondary.md)）。

- `createAccount(UUID uuid, String alias, boolean isPlayer)`
  — VaultUnlocked の `createAccount(uuid, name, boolean player)` と等価。
- `createSharedAccount(UUID uuid, String alias, UUID owner)`
  — VaultUnlocked の `createSharedAccount(uuid, name, owner)` と等価。

独自 API 経由と VaultUnlocked API 経由のどちらから作成しても同じ内部テーブルに格納される。
Player 口座も非 Player 口座も同一 API 表面で扱うことで、下流プラグインが Player / 非 Player を型で区別せず UUID で扱えるようにする。

### 3. VaultUnlocked の shared account を非 Player 口座に写像する

非 Player 口座（`system:*`、`company:*`、`pool:*` などの alias を持つ）を実装する内部テーブルとして、VaultUnlocked の shared account テーブルを使う。
`namespace` は VaultUnlocked の外には出さず、alias 文字列（`<namespace>:<key>`）の形でのみ露出する。
VaultUnlocked API から見ると、非 Player 口座は「UUID + name の shared account」として振る舞う。

- 非 Player 口座作成 = `createSharedAccount(uuid, alias, owner)` に相当。
  owner は初期メンバとして全 `AccountPermission` 付きで登録する。
- `AccountPermission` の管理は VaultUnlocked API に沿う（`updateAccountPermission`、`hasAccountPermission`、`addAccountMember` 等）。
- 独自 API では `AccountService.addMember` / `setPermission` / `hasPermission` を提供し、VaultUnlocked と等価な操作を露出する。

Economy 側は「誰が社長で誰が社員か」の semantic を知らない。
そのマッピングは Company プラグイン / Event 固有プラグインが行う。
Economy が持つのは「この口座に対してこの UUID がどの権限を持つか」だけ。

VaultUnlocked の `AccountPermission` を Modifier で参照できるようにする。
`TransferProbe.hasPermission(account, member, perm)` を提供し、Modifier が「非 owner による withdraw を veto」のような判定を書ける。

### 4. `AsyncEconomy` / `EconomyFutures` は実装しない

VaultUnlocked の非同期経路は Phase 1 では実装しない。
`ServicesManager` に `AsyncEconomy` の実装は登録しない。
`EconomyFutures` も同様。

これらを呼ぶプラグインは同期版の `Economy` にフォールバックする。
本イベントの想定ワークロードでは同期版で十分レイテンシが出る見込み。
将来 Phase 2 で必要が生じたら、独自 `TransferService` の async 版と併せて追加する余地は残す。

## 結果

- VaultUnlocked を採用している最新の外部プラグインが恩恵を受ける。
  今後 EssentialsX 系が VaultUnlocked に移行しても互換を保てる。
- 独自 `AccountService` の口座作成 API が VaultUnlocked と同じ shape になり、Player / 非 Player を型で区別しない一貫した扱いになる（[ADR-0013](./0013-uuid-primary-alias-secondary.md)）。
- 法人口座の owner/member 管理を独自実装せず、VaultUnlocked の `AccountPermission` に委ねられる。
  実装コストが減り、Vault 系ツールチェーンとの整合が上がる。
- 独自 SPI（`TransferService`、`ModifierRegistry`、`JeconTransferCompletedEvent`、`TransactionQueryService`）は残る。
  本イベントの経済制御はこちらが担う。
- VaultUnlocked の Async 経路を呼ぶプラグインは同期版にフォールバックする。
  完全対応を求めるプラグインは動かない可能性があるが、Phase 1 では許容する。
- `pluginName` は監査ログの metadata に載せるが、Modifier のディスパッチには使わない（VaultUnlocked の建前を守る）。
- shared account の owner/member semantic の解釈は Economy 外の責務として明示する。

## 選択しなかった代替案

- **VaultUnlocked を実装しない、旧 Vault のみ**：VaultUnlocked を採用しているエコシステムに乗れず、将来の互換で不利。実装コストは shared account 部分を独自に書く分と相殺する。
- **VaultUnlocked のみ実装し、旧 Vault を切る**：既存の EssentialsX 系（Vault 1.x を使う）が動かなくなる。両実装する方針を採る。
- **Shared account を使わず Named 口座を完全独自実装**：VaultUnlocked API の member/permission 管理経路を放棄することになり、Vault 系ツール（管理コマンド、GUI）が Named 口座を扱えない。
- **Async も実装する**：本イベントの想定ワークロードで不要。独自 `TransferService` を async 化する追加コストに見合わない。

## 関連

- [08-vault-bridge.md](../08-vault-bridge.md)
- [02-account-model.md](../02-account-model.md)
- [ADR-0006 Vault / VaultUnlocked も Modifier pipeline を通す](./0006-vault-through-modifier-pipeline.md)
