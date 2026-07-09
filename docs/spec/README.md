# Economy プラグイン仕様

イベント通貨の口座、振替、監査を担うプラグインの仕様書。
既存の [Jecon](https://github.com/f0reachARR/Jecon)（fork 元）に対して拡張する形で設計する（[ADR-0001](./adr/0001-fork-jecon-as-base.md)）。

## 位置付け

Economy プラグインは、他のプラグインから見た「残高の唯一の真実」を担う。
Job・Shop・Stock・Event 固有プラグインは、報酬計算やロジックを持ちつつも、金銭の移動はすべて Economy 経由で行う。

外部プラグインとの互換入口として、旧 Vault（`net.milkbowl.vault.economy.Economy`）と VaultUnlocked（`net.milkbowl.vault2.economy.Economy`）の両方を実装する。
両経路とも独自 `TransferService` に委譲し、同じ Modifier pipeline を通す（[ADR-0006](./adr/0006-vault-through-modifier-pipeline.md)、[ADR-0011](./adr/0011-vaultunlocked-shared-account-no-async.md)）。

## ドキュメント構成

- [01 概要と依存関係](./01-overview.md)
- [02 口座モデル（UUID + alias）](./02-account-model.md)
- [03 振替 API](./03-transfer-api.md)
- [04 トランザクション context と監査ログ](./04-context-and-log.md)
- [05 Modifier パイプライン](./05-modifier-pipeline.md)
- [06 公開 API と Bukkit Event](./06-public-api.md)
- [07 永続化](./07-persistence.md)
- [08 Vault ブリッジ](./08-vault-bridge.md)

## 外部リファレンス

- [VaultUnlocked API リファレンス](../references/vault-unlocked-api.md)

## ADR

- [ADR 索引](./adr/README.md)

## 下流 spec への波及

本 spec の [ADR-0013](./adr/0013-uuid-primary-alias-secondary.md) により、Shop / Stock 等の下流 spec に残る `AccountRef.Named(...)` などの型構文は書き換えが必要になる。
本 spec の改訂スコープは Economy 本体に限定し、下流の書き換えは別タスクで扱う。
alias 文字列表記（`system:tax_sink`、`company:acme` 等）はそのまま維持されるため、下流の慣習は継続する。
