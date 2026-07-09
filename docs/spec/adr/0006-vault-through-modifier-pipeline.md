# ADR-0006 Vault / VaultUnlocked 経由の振替も Modifier pipeline を通す

## ステータス

受け入れ（[ADR-0011](./0011-vaultunlocked-shared-account-no-async.md) で VaultUnlocked を追加採用）

## 背景

外部プラグインとの経済 API 互換の入口は 2 系統ある。

- 旧 Vault：`net.milkbowl.vault.economy.Economy`（Vault 1.x）
- VaultUnlocked：`net.milkbowl.vault2.economy.Economy`（Vault 2.x 後継、[ADR-0011](./0011-vaultunlocked-shared-account-no-async.md)）

いずれの経路も `depositPlayer` / `withdrawPlayer` / `transfer` 相当を持ち、EssentialsX pay、SellChest などが呼ぶ。
これらを Modifier pipeline に通すかどうかの選択肢がある。

### 案 A：Vault / VaultUnlocked 経由は素通し

`depositPlayer` は残高を直接動かす。Modifier pipeline を経由しない。

利点：

- Vault callers は今まで通り。副作用なし。
- パフォーマンスオーバーヘッドがない。

欠点：

- 日次キャップと cap_c 回路ブレーカーが Vault 経由呼び出しをすり抜ける。
- 「Job で稼いだ金は cap にかかるが、Vault 経由で貰った金は素通し」というアンバランスが発生する。
- 監査ログの source が「vault_bridge」なのに数値だけが記録される。

### 案 B：Vault / VaultUnlocked 経由も Modifier pipeline を通す

いずれの経路の呼び出しも内部で `TransferService.transfer(...)` に変換する。
`TransferContext.source` は経路別に振る。
Modifier pipeline を通り、pre-transfer フックが効く。

利点：

- 日次キャップと cap_c を全経路で適用できる。
- 監査ログが Modifier 適用後の実際の金額を反映する。
- 「金銭が動くパスは全て pipeline を通る」という不変を保てる。

欠点：

- Vault callers が意図せず Modifier に veto される可能性がある。
- パフォーマンスオーバーヘッド（pipeline 実行分）が乗る。
- Modifier 実装者は source 別の分岐を意識する必要がある。

## 決定

案 B を採る。両経路とも Modifier pipeline を通す。

- 旧 Vault の `depositPlayer(uuid, amount)` → `transfer(system:vault_bridge, player, amount, ctx)`。
  `ctx.source = "vault_bridge"`。
- 旧 Vault の `withdrawPlayer(uuid, amount)` → `transfer(player, system:vault_bridge, amount, ctx)`。
- VaultUnlocked の `deposit(pluginName, uuid, amount, ...)` → `transfer(system:vault_unlocked_bridge, player, amount, ctx)`。
  `ctx.source = "vault_unlocked"`。
- VaultUnlocked の `withdraw(pluginName, uuid, amount, ...)` → `transfer(player, system:vault_unlocked_bridge, amount, ctx)`。
- VaultUnlocked の `transfer(pluginName, from, to, amount, ...)` → `transfer(from, to, amount, ctx)`。
  片側が Player でも Named（shared account）でも同じ経路。
- 両経路とも `ctx.metadata["vault_caller"]` に呼び出し元プラグイン名を best-effort で入れる（[08-vault-bridge.md](../08-vault-bridge.md)）。
  VaultUnlocked では引数の `pluginName` が明示的にあるため、それを優先する。
- 旧 Vault と VaultUnlocked を別 source（`vault_bridge` / `vault_unlocked`）に分ける理由は、Modifier 側で経路を区別したい場合の粒度確保。
  同一扱いで良い場合は Modifier 側で両方に反応する条件を書けばよい。

Modifier が `Veto` を返した場合、Vault の戻り値は `EconomyResponse.FAILURE` にマップする。
`errorMessage` に Modifier の `reason` を入れる。

## 結果

- 日次キャップ、cap_c 回路ブレーカーが両経路の呼び出しに効く。
  Job 経由と Vault 系経由で不均衡が発生しない。
- 監査ログに Vault 系呼び出しがドメイン理由付きで記録される。
  `metadata.vault_caller`（旧 Vault は best-effort、VaultUnlocked は引数から）で呼び出し元プラグインを追跡できる。
- Modifier 実装者は必要なら source 別の分岐を書ける。
  例：`if (ctx.source().equals("vault_bridge") || ctx.source().equals("vault_unlocked"))` で Vault 系全般に反応。
- パフォーマンスオーバーヘッドは Modifier の数と処理内容次第。
  イベント期間のワークロード（想定 100 名程度）では問題にならない見込み。
- 一部の Vault callers（管理コマンド系）が意図せず veto されるリスクは残る。
  対策として、Modifier は `metadata.vault_caller` を見て admin 系を素通しする書き分けを推奨する。

## 選択しなかった代替案

- **案 A（素通し）**：cap_c と日次キャップの抜け穴を許容できない。イベントの経済制御が Vault 抜け道で崩れる。
- **旧 Vault と VaultUnlocked を同一 source にする**：Modifier で経路別に振る舞いを分けたいケース（例：VaultUnlocked では `pluginName` が明示されるので信頼度が高い、旧 Vault は best-effort なので疑って処理）が書けなくなる。source を分ける。
- **Vault callers ごとに設定で Modifier 対象/対象外を切り替える**：呼び出し元プラグイン名の推定が best-effort で確実ではないため、ホワイトリスト/ブラックリストの管理コストが高い。Modifier 側の分岐で解決する方が明快。

## 関連

- [05-modifier-pipeline.md](../05-modifier-pipeline.md)
- [08-vault-bridge.md](../08-vault-bridge.md)
- [ADR-0011 VaultUnlockedAPI 採用](./0011-vaultunlocked-shared-account-no-async.md)
