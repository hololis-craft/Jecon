# ADR-0007 overdraft オプションを持たせる

## ステータス

受け入れ

## 背景

`transfer` のデフォルト挙動は「残高不足なら `InsufficientFunds` を返して失敗」だが、次のケースではマイナス残高を許容してでも執行したい。

- **税徴収**：プレイヤーの残高より税額が大きい場合、徴収は成立させて負残高にする（負残高の埋め合わせは次回 deposit で行われる）。
- **強制ペナルティ**：管理コマンドやゲーム内ペナルティで、残高に関わらず一定額を差し引く。
- **`system:vault_bridge` 口座**：無から発行される Vault の deposit を受けるため、常に負残高で運用する。

一律に負残高を許容してしまうと、日常のプレイヤー取引でうっかりマイナスになる事故が起きる。
かといって「税だけ Economy 本体で特別扱い」するのは、cap_c を外部化した理由（[ADR-0005](./0005-transfer-modifier-external-only.md)）と整合しない。

## 決定

`TransferContext` に `overdraft` フラグを持たせる。

- デフォルトは `false`。残高不足なら `InsufficientFunds` を返す。
- `withOverdraft()` で `true` に。この場合、残高不足でも成功して負残高になる。
- Batch 内の一部 leg だけ overdraft、他は通常挙動、という混在は許さない。
  `TransferContext` 全体に対する属性。
- パーミッション `jecon.transfer.overdraft` を要求する（コマンド経由の場合）。
  API 経由（プラグイン間）ではパーミッション判定は行わない。

システム口座（`system:vault_bridge` など）は `config.yml` で `always_overdraft: true` を設定し、その口座を from とする振替はコード上のフラグに関わらず overdraft を強制する（[08-vault-bridge.md](../08-vault-bridge.md)）。

## 結果

- 税徴収 Modifier は `ctx.withOverdraft()` を設定した leg を追加できる。
  税率が高くて残高不足になっても徴収が成立する。
- `system:vault_bridge` の運用が単純化される。
  設定で always_overdraft にすれば、Vault deposit 呼び出しで vault_bridge が負に沈むのを常時許容できる。
- 通常のプレイヤー取引ではデフォルトの `false` により、意図しない負残高が発生しない。
- 負残高からの復帰は特別扱いしない。次回の deposit が普通に足し算される。
- 監査ログでは overdraft フラグを metadata に記録する（`metadata["overdraft"] = "true"`）。
  事後に「どの振替が負残高を作ったか」を追跡できる。

## 選択しなかった代替案

- **overdraft を口座属性にする**（`Account.allowNegativeBalance`）：口座単位で常時許可すると、税徴収のような「特定の振替だけ」の粒度で制御できない。system 口座には config で口座属性として持たせるが、通常の transfer に対しては context フラグで制御する。
- **`transfer` メソッドの引数を分ける**（`transferWithOverdraft`）：メソッド数が増える。`TransferContext` に集約する方が API が単純。
- **常に overdraft を許容する（残高チェックしない）**：意図しない負残高が広まるリスクが高い。デフォルトは安全側に倒す。

## 関連

- [03-transfer-api.md](../03-transfer-api.md)
- [04-context-and-log.md](../04-context-and-log.md)
