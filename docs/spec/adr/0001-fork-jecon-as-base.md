# ADR-0001 既存 Jecon の fork をベースにする

## ステータス

受け入れ

## 背景

Minecraft サーバでの経済プラグインは既に成熟した実装が複数ある。
Vault、EssentialsX Economy、CMI Economy、Jecon などが代表例で、いずれもプレイヤー残高の保持、コマンド、Vault 対応を持つ。

本イベント（21 日間の経済イベント）は次を必要とする。

- プレイヤー口座に加え、法人・イベントプール・カジノハウスなどの非プレイヤー口座
- 振替の source と metadata による監査
- 振替の pre-フック（税、日次キャップ、cap_c 回路ブレーカー）
- 既存プラグインとの互換（Vault、EssentialsX pay など）

これらを一から実装する案と、既存の軽量プラグインを fork して拡張する案がある。

一から書く場合、Vault 互換、Bukkit との統合、MySQL 接続プール、LazyWrite の multi-server safety など、既製実装で解決済みのボイラープレートを全て書き直すことになる。
一方で、既存プラグインを fork する場合、拡張点の設計を既存アーキテクチャに寄せる制約は入るが、実装量は大幅に減る。

`~/workspace/Jecon` にすでに fork したツリーがあり、以下の性質を確認済み。

- Java 21、Paper 対応、Vault 対応
- `BalanceRepository` インタフェースで API を公開
- `LazyRepository` による delta 書き込みで multi-server safe
- HikariCP + MySQL / SQLite
- Brigadier コマンド
- 内部通貨表現は `long`（cent = ×100）で浮動小数の精度問題を回避
- 既存の `transaction_log` テーブルはあるが 4 列のみで拡張が必要

Jecon の基本構造は本仕様の要件と大枠で合致する。

## 決定

Economy プラグインは Jecon の fork をベースにする。
`~/workspace/Jecon` の tree で拡張作業を行う。

拡張の方針：

- 既存 `BalanceRepository` インタフェースは維持し、後方互換を保つ（[ADR-0010](./0010-backward-compat-balancerepository.md)）。
- 追加機能（`AccountService`、`TransferService`、`TransferModifier`、監査ログ拡張）は新規インタフェースとして重ねる。
- 内部の `long` 表現・delta 書き込み・HikariCP など、Jecon の設計判断はそのまま流用する。
- テーブルスキーマは既存の `account`、`balance` を残し、`transaction_log` は差し替える（[07-persistence.md](../07-persistence.md)）。

## 結果

- 実装工数が大幅に減る。
  Vault ブリッジ、DB 接続、コマンドは fork の実装が使える。
- 既存 Jecon のユーザ（存在する場合）に対して破壊的変更を局所化できる。
  `BalanceRepository` の呼び出しは無改造で通る。
- 一方で、Jecon の設計に引きずられる部分がある。
  例：`AbstractRepository` の final メソッドが多く、フック挿入の余地が限定的。
  必要に応じて `AbstractRepository` を書き換える。
- 上流の Jecon から取り込む更新は基本的に無い（fork 独立）。
  security fix があれば手動で取り込む。

## 選択しなかった代替案

- **フルスクラッチ**：Vault ブリッジや Lazy write のような hairy な部分を書き直す工数を払う価値がない。
- **EssentialsX Economy への hook**：EssentialsX のライセンス（GPL）と、我々のイベントプラグインのライセンスを揃える必要が出る。また EssentialsX は economy 以外の巨大な機能を含み、依存が重い。
- **Vault だけを実装して他プラグイン任せ**：非プレイヤー口座と Modifier pipeline を Vault インタフェース内で表現できない。
