# Vault / VaultUnlocked 統合

Economy プラグインは外部プラグインとの互換入口として 2 系統の API を実装する。

- **旧 Vault**（`net.milkbowl.vault.economy.Economy`、Vault 1.x）：EssentialsX、SellChest などが依存する既存のデファクト。
- **VaultUnlocked**（`net.milkbowl.vault2.economy.Economy`、Vault 2.x 後継）：非 Player 口座と shared account を持つ後継 API（[ADR-0011](./adr/0011-vaultunlocked-shared-account-no-async.md)、[references/vault-unlocked-api.md](../references/vault-unlocked-api.md)）。

Economy 側の口座モデル（UUID 主・alias 副）は VaultUnlocked の shape に揃えている（[02-account-model.md](./02-account-model.md)、[ADR-0013](./adr/0013-uuid-primary-alias-secondary.md)）ため、VaultUnlocked 経由は「互換ブリッジ」というより **主経路そのもの** を Vault 側インタフェースで叩く形になる。
旧 Vault のみ、片側 API を transfer に変換する意味での「ブリッジ」が残る。

両経路とも独自 `TransferService` に委譲し、Modifier pipeline を通す（[ADR-0006](./adr/0006-vault-through-modifier-pipeline.md)）。
Jecon fork 本体が両インタフェースを直接実装する。

## 対向口座

`transfer` は 2 口座間の振替として扱う設計。
旧 Vault や VaultUnlocked の `depositPlayer` / `withdrawPlayer` / `deposit` / `withdraw` のように片側だけを動かす API は、内部で system 口座を対向に立てる。

- `system:vault_bridge` 旧 Vault 経由の対向
- `system:vault_unlocked_bridge` VaultUnlocked 経由の片側 API の対向

VaultUnlocked の `transfer(pluginName, from, to, amount)` は両端が指定されるため system 口座を使わない。

これらの system 口座は残高がマイナスに沈むことが多い（deposit の発行元として使われるため）。
`config.yml` で常時 overdraft を許可する。

```yaml
system_accounts:
  vault_bridge:
    always_overdraft: true
  vault_unlocked_bridge:
    always_overdraft: true
```

`resident_in_cache` オプションはキャッシュ機構自体を廃止したため（[ADR-0012](./adr/0012-drop-lazy-repository.md)）持たない。

## 旧 Vault API のマッピング

旧 Vault は口座を UUID + player 名で扱う。
以下は既存の慣習をそのまま踏襲する。

| Vault メソッド | 挙動 |
|---|---|
| `depositPlayer(uuid, amount)` | `transfer(system:vault_bridge, player(uuid), amount, ctx)` |
| `withdrawPlayer(uuid, amount)` | `transfer(player(uuid), system:vault_bridge, amount, ctx)` |
| `has(uuid, amount)` | `BalanceRepository.has(uuid, amount)` |
| `getBalance(uuid)` | `BalanceRepository.getDouble(uuid)` |
| `hasAccount(uuid)` | `AccountService.exists(uuid)` |
| `createPlayerAccount(uuid)` | `AccountService.createAccount(uuid, playerName, true)` |

`ctx.source = "vault_bridge"`、`ctx.metadata["vault_caller"]` に best-effort で呼び出し元プラグイン名を入れる（後述）。

## VaultUnlocked API のマッピング

VaultUnlocked は口座を `UUID accountID` + optional `String name` で扱う。
Player と非 Player は `createAccount(uuid, name, boolean player)` の `player` フラグで区別する。
Economy 側の内部モデルもまったく同じ shape のため、マッピングは 1:1 に近い（[references/vault-unlocked-api.md](../references/vault-unlocked-api.md)）。

| VaultUnlocked メソッド | 挙動 |
|---|---|
| `createAccount(uuid, name, player=true)` | `AccountService.createAccount(uuid, name, true)` |
| `createAccount(uuid, name, player=false)` | `AccountService.createAccount(uuid, name, false)`（`name` は `<namespace>:<key>` 形式必須） |
| `deleteAccount(pluginName, uuid)` | `AccountService.delete(uuid)` |
| `renameAccount(uuid, name)` | `AccountService.rename(uuid, name)` |
| `getAccountName(uuid)` | `AccountService.get(uuid).map(Account::alias)` |
| `balance(pluginName, uuid, ...)` | 該当口座の残高 |
| `deposit(pluginName, uuid, amount, ...)` | `transfer(system:vault_unlocked_bridge, uuid, amount, ctx)` |
| `withdraw(pluginName, uuid, amount, ...)` | `transfer(uuid, system:vault_unlocked_bridge, amount, ctx)` |
| `transfer(pluginName, from, to, amount, ...)` | `transfer(from, to, amount, ctx)` |
| `createSharedAccount(pluginName, uuid, name, owner)` | `AccountService.createSharedAccount(uuid, name, owner)` |
| `isAccountMember` / `updateAccountPermission` / `hasAccountPermission` | `AccountService.hasPermission` / `setPermission` 等に 1:1 |
| `accountsWithOwnerOf` / `accountsWithMembershipTo` / `accountsWithAccessTo` | 内部の member テーブルを UUID で SELECT |

`ctx.source = "vault_unlocked"`。
`ctx.metadata["plugin_name"]` に引数の `pluginName` を入れる。
Javadoc の建前（logging/diagnostics only、business logic に影響してはならない）を守り、Modifier のディスパッチには使わない。
`ctx.metadata["vault_caller"]` にも同じ値を入れ、Vault 系全体でのキー統一を保つ。

多通貨引数（`String currency`）は Phase 1 では単一通貨のみサポートし、デフォルト通貨以外を渡された場合は `NOT_IMPLEMENTED` を返す。
world 引数（`String worldName`）も無視する（world スコープなしの単一残高）。

### 非 Player 口座と shared account

Economy の非 Player 口座は VaultUnlocked の shared account 実装をそのまま利用する（[02-account-model.md](./02-account-model.md)、[ADR-0011](./adr/0011-vaultunlocked-shared-account-no-async.md)）。

- **UUID の一貫性**：非 Player 口座の UUID は alias（`<namespace>:<key>`）から type-3 で決定的に導出する。
  VaultUnlocked API から見ると「その UUID の shared account」として扱える。
- **namespace は VaultUnlocked に露出しない**：VaultUnlocked API から見た口座は `UUID + name` のペアで、内部の `namespace` 派生列は表に出ない。
- **owner/member**：VaultUnlocked の `AccountPermission` enum（DEPOSIT/WITHDRAW/BALANCE/TRANSFER_OWNERSHIP/INVITE_MEMBER 等）に沿って permission を保存する。
  `createSharedAccount(uuid, alias, owner)` の owner は初期メンバとして全 permission 付きで登録する。

### `refFromUuid`

VaultUnlocked から受け取った UUID を、内部で `Account` に lookup して alias を復元するヘルパ。
`AccountService.get(uuid)` の結果を使うか、cache 無し実装なら都度 DB を引く。
alias は表示用途と監査ログでのみ必要で、hot path での lookup は避けたい場合、`AccountService.isPlayer(uuid)` のような軽量 API を将来追加する余地はある。

### AsyncEconomy / EconomyFutures

VaultUnlocked の非同期経路（`AsyncEconomy`、`EconomyFutures`）は Phase 1 では実装しない（[ADR-0011](./adr/0011-vaultunlocked-shared-account-no-async.md)）。
`ServicesManager` にこれらの実装を登録しない。
呼ぶプラグインは同期版の `Economy` にフォールバックする。

将来 Phase 2 で必要が生じた場合は、独自 `TransferService` の async 版と併せて追加する。

## 呼び出し元プラグインの推定

旧 Vault は呼び出し元プラグイン情報が API で渡らない。
`StackWalker` で呼び出しスタックを遡り、Bukkit の `PluginClassLoader` を持つクラスを探す。

```java
Optional<String> guessCaller() {
  return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
      .walk(frames -> frames
          .map(StackWalker.StackFrame::getDeclaringClass)
          .filter(c -> c.getClassLoader() instanceof PluginClassLoader pcl && pcl.getPlugin() != null)
          .map(c -> ((PluginClassLoader) c.getClassLoader()).getPlugin().getName())
          .filter(name -> !"Jecon".equals(name) && !"Vault".equals(name))
          .findFirst());
}
```

Java の内部仕様に依存する best-effort 実装。
Paper のバージョン更新で壊れる可能性があるため、失敗時は `"unknown"` を返す。

VaultUnlocked は `pluginName` を引数で明示するため、`StackWalker` は使わず引数の値をそのまま採用する。
`config.yml` で完全に無効化できる。

```yaml
vault_bridge:
  guess_caller: true                    # 旧 Vault のみ
  guess_caller_max_depth: 20
```

## Modifier の適用

両経路の呼び出しとも `TransferModifier` pipeline を通る。
Modifier 側は `ctx.source()` で経路を区別できる。

代表的なユースケース：

- **日次キャップを全経路に適用**：source を `job` と `vault_bridge` と `vault_unlocked` の 3 つに反応する Modifier を書く。
- **cap_c 回路ブレーカーを全経路に適用**：cap_c は総流通量を見るので、Vault 系も一律で hook する。
- **VaultUnlocked からの transfer は素通し**：`source.equals("vault_unlocked")` で `Pass`、他 source では通常適用。

Modifier が Vault 経由呼び出しを `Veto` した場合、Vault の戻り値は `EconomyResponse.ResponseType.FAILURE` にマップする。
`errorMessage` は Modifier の `Veto.reason()` を使う。

## Vault 系の戻り値マッピング

`TransferResult` → `EconomyResponse` / `MultiEconomyResponse` は次のようにマップする。

| TransferResult | 旧 Vault | VaultUnlocked |
|---|---|---|
| `Success` | `SUCCESS`, amount=leg.amount, balance=new balance | `SUCCESS` |
| `InsufficientFunds` | `FAILURE`, errorMessage="Insufficient funds" | `FAILURE`, errorMessage="Insufficient funds" |
| `Vetoed(id, reason)` | `FAILURE`, errorMessage=reason | `FAILURE`, errorMessage=reason |
| `AccountMissing` | `NOT_IMPLEMENTED` | `FAILURE`, errorMessage="Account missing" |
| `InvalidAmount` | `FAILURE`, errorMessage=reason | `FAILURE`, errorMessage=reason |

## Bank API（旧 Vault）

旧 Vault の Bank API（`createBank`、`bankBalance`、`bankDeposit`、`bankWithdraw`）は本 spec で対応する。
非 Player 口座を alias `bank:<name>` で作る（`AccountService.createSharedAccount(uuid, "bank:<name>", owner)`）。
UUID は alias から type-3 で導出する。

```java
public EconomyResponse createBank(String name, String player) {
  UUID owner = uuidOf(player);
  String alias = "bank:" + name.toLowerCase(Locale.ROOT);
  UUID uuid = UUID.nameUUIDFromBytes(alias.getBytes(StandardCharsets.UTF_8));
  accountService.createSharedAccount(uuid, alias, owner);
  return successResponse();
}
```

## 起動順序

Vault と VaultUnlocked はいずれも optional dependency。
Jecon の `onEnable` で、`Bukkit.getPluginManager().getPlugin(...)` により以下の順で検出する。

1. VaultUnlocked を検出したら hook（優先）。
2. 旧 Vault を検出したら hook（併存）。

両方存在する場合は両方に登録する。
外部プラグインがどちらを叩くかは呼び出し側の依存次第。

## 関連 ADR

- [ADR-0006 Vault / VaultUnlocked も Modifier pipeline を通す](./adr/0006-vault-through-modifier-pipeline.md)
- [ADR-0010 BalanceRepository の後方互換](./adr/0010-backward-compat-balancerepository.md)
- [ADR-0011 VaultUnlockedAPI 採用](./adr/0011-vaultunlocked-shared-account-no-async.md)
- [ADR-0013 口座の主キーを UUID とし alias を副次表現とする](./adr/0013-uuid-primary-alias-secondary.md)
