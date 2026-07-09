# ADR-0010 BalanceRepository の後方互換

## ステータス

受け入れ

## 背景

Jecon の既存 `BalanceRepository` は他プラグインから直接呼ばれている可能性がある。
また、Vault の `Economy` インタフェース経由でも実質同じ経路が使われる。
これらの呼び出しに対する扱いを決める必要がある。

### 案 A：既存 API を deprecate して切り替えを迫る

`BalanceRepository.deposit(uuid, amount)` は @Deprecated にし、新 API（`TransferService.transfer`）への移行を呼び出し側に強いる。

問題：

- 我々が管理しない外部プラグイン（Vault callers）が動かなくなる。
- イベントまでの期間で全プラグインを移行するのは非現実的。

### 案 B：既存 API は残すが素通し（Modifier を通さない）

`BalanceRepository.deposit` は従来通り、単純に残高を増やす。
Modifier pipeline や監査ログの拡張は通らない。

問題：

- Vault 経由呼び出しに cap_c や日次キャップが効かない。
  [ADR-0006](./0006-vault-through-modifier-pipeline.md) の方針と矛盾する。

### 案 C：既存 API を新 API 経由のシムに置き換える

`BalanceRepository.deposit(uuid, amount)` の内部実装を `TransferService.transfer(system:legacy_source, player, amount, ctx)` に置き換える。
呼び出し側は無改造、しかし Modifier pipeline と拡張監査ログの恩恵を受ける。

## 決定

案 C を採る。

### 既存メソッドの内部マッピング

| BalanceRepository のメソッド | 内部実装 |
|---|---|
| `deposit(uuid, amount)` | `transfer(system:legacy_source, player(uuid), amount, ctx.source="legacy")` |
| `withdraw(uuid, amount)` | `transfer(player(uuid), system:legacy_sink, amount, ctx.source="legacy")` |
| `set(uuid, amount)` | 差分を計算し `transfer` に変換。差分が正なら legacy_source から、負なら legacy_sink へ。 |
| `has(uuid, amount)` | 従来通り。read only。 |
| `getDouble/getDecimal` | 従来通り。 |
| `hasAccount/createAccount/removeAccount` | 従来通り。ただし内部で `AccountService` に委譲。 |

- `ctx.source = "legacy"` を必ず設定。
- `ctx.metadata["legacy_method"]` に `"deposit"` / `"withdraw"` / `"set"` を入れる。
- 呼び出し元プラグインは best-effort で `metadata["legacy_caller"]` に入れる（[08-vault-bridge.md](../08-vault-bridge.md) の `guessCaller` と同じ実装）。

### Vault 経由

Vault の `Economy.depositPlayer` は直接 `TransferService` を呼ぶ（[08-vault-bridge.md](../08-vault-bridge.md)）。
`ctx.source = "vault_bridge"` で、`BalanceRepository` 経由のシムとは別 source を割り当てる。
これにより「Vault 経由」と「BalanceRepository 直接呼び出し」を監査ログ上で区別できる。

### 戻り値の整合

`BalanceRepository.deposit(uuid, amount)` は `boolean` を返す。
新 API の `TransferResult` のうち、次のようにマップする。

| TransferResult | boolean |
|---|---|
| `Success` | `true` |
| `InsufficientFunds` | `false`（withdraw のみ） |
| `Vetoed` | `false` |
| `AccountMissing` | `false` |
| `InvalidAmount` | `false`（かつログに WARN） |

Modifier で veto された場合、既存呼び出し側は「単に deposit が失敗した」としか認識できない。
これは案 C の限界だが、監査ログには veto の理由が残るので事後追跡は可能。

### `set` の扱い

`set(uuid, amount)` はドメイン理由不明の「絶対値上書き」で、Modifier pipeline との相性が悪い。
内部では現在残高との差分を計算し、`transfer` に変換する。
Modifier が veto した場合、set は失敗する。
`/jecon set` コマンドは admin 権限を持つコマンドなので、Modifier は admin 経路を素通しするよう書く慣習にする（source を見て `admin` なら `Pass` を返す）。

## 結果

- 既存プラグインは無改造で動く。
- Vault 経由も含めて全ての金銭移動が Modifier pipeline と監査ログを通る。
  日次キャップと cap_c 回路ブレーカーが全経路で効く。
- 監査ログの `source` で「新 API 経由」「BalanceRepository 経由」「Vault 経由」を区別できる。
- `set` を含む一部の従来 API は意味論的にモデル化しづらいが、差分計算での代替で対処。
- Modifier 実装者は source 別の分岐を書く必要がある（`legacy`、`vault_bridge`、`admin` を素通ししたい場合など）。

## 選択しなかった代替案

- **案 A（deprecate）**：期間内に全プラグインを移行させるのは非現実的。
- **案 B（素通し）**：Modifier のカバレッジが穴だらけになり、経済制御が抜ける。

## 関連

- [03-transfer-api.md](../03-transfer-api.md)
- [06-public-api.md](../06-public-api.md)
- [08-vault-bridge.md](../08-vault-bridge.md)
