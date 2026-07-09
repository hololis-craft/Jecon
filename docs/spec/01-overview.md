# 概要と依存関係

## スコープ

Economy プラグインが責任を持つのは次の 4 点。

- **口座**：プレイヤー口座と非プレイヤー口座（法人、イベントプール、カジノハウス、税収 sink など）の作成、削除、残高保持。
- **振替**：口座間の原子的な残高移動。単一 leg と複数 leg（バッチ）の双方。
- **監査**：振替の source、metadata、金額、時刻を含む完全ログ。
- **拡張点**：他プラグインが振替の前後に差し込む Modifier と Bukkit Event。

## スコープ外

Economy プラグインが持たないもの。

- **税率、日次キャップ、cap_c 回路ブレーカーの実装本体**：外部プラグインが Modifier として登録する（[ADR-0005](./adr/0005-transfer-modifier-external-only.md)）。
- **通貨換算やインフレ計算**：本イベントは単一通貨のみで扱う。
- **報酬計算（Job のパイプラインなど）**：呼び出し側の責務。Economy には確定した金額のみが届く。
- **UI**：残高表示コマンドは持つが、リッチな家計簿 UI は提供しない。

## 依存関係

- **Paper 1.21+**：Job プラグインと同じ前提（[Job ADR-0008](../../Jobs/spec/adr/0008-paper-1-21-only.md)）。
- **Vault**：optional。存在すれば `net.milkbowl.vault.economy.Economy`（Vault 1.x）を実装する。
- **VaultUnlocked**：optional runtime だが、口座モデルの shape を採用する主参照 API（[ADR-0011](./adr/0011-vaultunlocked-shared-account-no-async.md)、[references/vault-unlocked-api.md](../references/vault-unlocked-api.md)）。存在すれば `net.milkbowl.vault2.economy.Economy` を実装する。非 Player 口座は VaultUnlocked の shared account に写像する。
- **MySQL**：Phase 1 の永続化。SQLite は Jecon 由来の実装を残しつつも、複数 Paper インスタンス構成が視野に入ればテスト用途に降格する。詳細は [07-persistence.md](./07-persistence.md)。

## 呼び出し側の想定

- **Job**：`transfer(system:job_pool, player, amount, ctx)`。source は `job`。
- **Shop**：`transferBatch([player → owner, player → tax_sink], ctx)`。source は `shop`。
- **Stock**：`transferBatch([company → each_holder], ctx)` を配当タイミングで発行。source は `stock_dividend`。
- **Casino**：`transfer(player, house)` と `transfer(house, player)`。source は `casino`。
- **税徴収**：`transfer(player, system:tax_sink, amount, ctx.withOverdraft())`（[ADR-0007](./adr/0007-allow-overdraft-optional.md)）。source は `tax`。
- **Vault / VaultUnlocked 経由**：EssentialsX などの Vault 系呼び出しを、`system:vault_bridge` または `system:vault_unlocked_bridge` 口座との振替に翻訳する（[ADR-0006](./adr/0006-vault-through-modifier-pipeline.md)、[08-vault-bridge.md](./08-vault-bridge.md)、[ADR-0011](./adr/0011-vaultunlocked-shared-account-no-async.md)）。

## Job プラグインとの関係

Job プラグインは Economy を必須依存として持つ。
`JobActionPaidEvent` は Economy への振替が完了した時点で発火する。
Job 側の `JobRewardModifier` は報酬額を決めるパイプライン、Economy 側の `TransferModifier` は振替を実際に成立させる直前に走るパイプライン。
両者は別レイヤで、直列に走る。

Job 側で税を差し引きたい場合は、Job の `Splitter` で税分を分離するか、Economy 側に `TransferModifier` を登録して差し引くかの二択。
Job から見て一貫した監査が欲しいので、税は Economy 側の Modifier で扱う設計を推奨する。

## Fork 元との差分

Jecon（fork 元）から残すもの。

- `BalanceRepository` インタフェース。既存プラグインの呼び出しを壊さない（[ADR-0010](./adr/0010-backward-compat-balancerepository.md)）。
- Vault 経由の `Economy` 実装。

Jecon から改める / 廃止するもの。

- `LazyRepository`（delta 書き込みキャッシュ）は廃止。Sync 単一モードに一本化する（[ADR-0012](./adr/0012-drop-lazy-repository.md)）。
- 口座キーを Player UUID 単一から **UUID 主・alias 副** に整理する（[ADR-0013](./adr/0013-uuid-primary-alias-secondary.md)）。
  Player の alias は Minecraft 名、非 Player の alias は `<namespace>:<key>`（例：`system:tax_sink`、`company:acme`）。
  外部プラグインは常に UUID で口座を指定する。

Jecon にないものを本仕様で追加する。

- 非 Player 口座（`system:*`、`company:*`、`pool:*` などの alias を持つ）。VaultUnlocked の shared account に写像する。
- 口座作成 API を VaultUnlocked の shape（`createAccount(uuid, name, boolean player)` / `createSharedAccount(uuid, name, owner)`）に揃える（[ADR-0011](./adr/0011-vaultunlocked-shared-account-no-async.md)）。
- 原子的な `transfer` と `transferBatch`。
- `TransferContext`（source + metadata）と拡張された監査ログ。
- `TransferModifier` パイプライン。
- `JeconTransferCompletedEvent`。
- 旧 Vault と VaultUnlocked の両実装。Vault 経由と VaultUnlocked 経由の呼び出しに Modifier を適用する経路。

後方互換の詳細は [ADR-0010](./adr/0010-backward-compat-balancerepository.md)。
