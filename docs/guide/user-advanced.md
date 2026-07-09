# 利用者向けガイド（発展機能）

日常的にはあまり使わないが、知っておくと便利なコマンドや挙動をまとめる。
基本機能は [利用者向け（基本機能）](./user-basic.md) を参照。

## 長者番付を見る — `/money top`

サーバー内の残高上位を一覧表示する。

```
/money top [page]
```

- `page` は 1 始まりの整数。省略すると 1 ページ目（上位 10 件）が出る。
- 1 ページあたり 10 件表示される。

例:

```
/money top 2
====== Billionaires ranking (Page: 2) ======
11: Alice (10,000 dollars 0 cents)
12: Bob   (9,876 dollars 50 cents)
...
```

### 権限による見え方の違い

- **通常**: 「プレイヤー口座」のみがランキングに載る（非プレイヤー口座は隠される）。
- **`jecon.viewnonplayer` を持っている場合**: 非プレイヤー口座（`system:*`、`company:*`、`pool:*` など）もランキングに含めて表示される。

サーバー側で `hideNonPlayerAccounts: false` に設定されているサーバーでは、権限に関係なく全口座が見える。

### 必要な権限

- `jecon.top`（デフォルトは OP のみ）

一般プレイヤーが `/money top` を使えるかどうかはサーバー方針次第。使いたい場合は管理者に相談する。

## 他プレイヤーの残高を見る — `/money show <player>`

指定したプレイヤーの残高を表示する。

```
/money show <player>
```

例:

```
/money show Alice
Alice balance: 10,000 dollars 0 cents
```

### 必要な権限

- `jecon.show.other`（デフォルトは OP のみ）

## 通貨表示の読み方

Jecon は「メジャー単位」と「マイナー単位」の 2 段構成で通貨を表現している。

- **メジャー**: 整数部分（例: ドル、円）
- **マイナー**: 小数部分（例: セント）
- 内部的には `major * 100 + minor` のセント値として保持される。

`config.yml → format` によってサーバーごとに表示形式が変わる。よくあるフォーマット例:

| 例 | フォーマット文字列 |
| --- | --- |
| `1 dollar 20 cents` | `{major} {majorcurrency} {minor} {minorcurrency}` |
| `$ 1.20` | `$ {major}.{minor}` |
| `1円` | `{major}円` |

小数の桁揃え（例: `3.4` にするか `3.40` にするか）はサーバー側の `minorType` 設定に依存する。

## 送金は必ずしも「そのまま」届くわけではない

`/money pay` や、他プラグイン経由の送金は、内部の **Transfer パイプライン** を通っている。
サーバーによっては、次のような「割り込み」が入る場合がある。

- **税・手数料**: 送金の一部が別口座（`system:tax_sink` など）に流れる。
- **上限（cap）**: 一定額を超える送金は自動的にカットされる。
- **拒否（veto）**: 特定条件で送金そのものが弾かれる（メッセージ例: `Vetoed by modifier '<modifier>': <reason>`）。

これは Jecon の Modifier パイプラインの仕組みによるもので、サーバー側の経済ポリシー次第。
「送ったはずの額と、届いた額が違う」「送金が拒否された」といった現象に遭遇したら、
サーバーの経済ルール（Wiki、Discord、Regulation 等）を確認するか、管理者に問い合わせる。

技術的な詳細は [spec/05-modifier-pipeline.md](../spec/05-modifier-pipeline.md) を参照。

## 他プラグインとの連携（Vault 経由）

Jecon は Vault 経済プラグインとして振る舞う。
つまり、Vault に対応した以下のようなプラグインからは、透過的に Jecon の残高が使われる。

- ショッププラグイン（EssentialsX, ChestShop 等）での購入・売却
- ジョブプラグイン（Jobs Reborn 等）での報酬支給
- 家プラグイン、ワープ有料化プラグインなどでの支払い

これらのプラグインが「残高が足りません」等のメッセージを出す場合、
その残高判定は Jecon が担当している。`/money` で確認できる残高と一致するはず。

VaultUnlocked（Vault 2.x）にも対応している。両方の Vault API を同時にブリッジできる。

## 口座がない状態について

以下のようなケースで、稀に「口座がない」状態に陥ることがある。

- サーバーが `createAccountOnJoin: false` で運用されている（初回参加時に自動作成しない設定）
- 管理者が明示的に `/money remove` で口座を削除した
- データベースの障害から復旧した直後

`/money` を実行した際に `Account not found: <name>` と出る場合は、管理者に `/money create <あなた>` を依頼する。

## 送金失敗時のエラー一覧（利用者から見える範囲）

| メッセージ | 状況 |
| --- | --- |
| `Argument is missing.` | 引数が足りていない |
| `Invalid argument: <value>` | 引数の値が不正（自分自身への送金など） |
| `Player not found: <name>` | 指定した名前のプレイヤーが存在しない |
| `Account not found: <name>` | プレイヤーは存在するが口座がない |
| `The balance of the account is not enough.` | 残高不足 |
| `You don't have permission!!` | 必要な権限がない |

## タブ補完

Brigadier ベースの補完が働く。

- `/money pay <TAB>`: 送金対象のプレイヤー名（オンライン + 過去のログイン記録から）
- 非プレイヤー口座は、`jecon.viewnonplayer` を持っていない限りタブ補完に出てこない
  （サーバーの `hideNonPlayerAccounts: true` 時）
