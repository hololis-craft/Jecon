# 管理者向けガイド

サーバー管理者向けに、Jecon のインストール、設定、口座管理、権限、データベース、Vault 連携、およびトラブルシューティングをまとめる。
プレイヤー視点の利用方法は [利用者向けガイド](./user-basic.md) を参照。

## インストール

1. リリース JAR (`Jecon-<version>.jar`) を `plugins/` に配置する。
2. サーバーを起動する。初回起動で `plugins/Jecon/config.yml` などが生成される。
3. 必要に応じて設定を編集し、`/money reload` するかサーバーを再起動する。

Vault / VaultUnlocked を使う経済ハブが必要な場合は、Vault 本体もセットで導入する（`plugin.yml` の `loadbefore` で Jecon が先にロードされるように設定済み）。

## コマンドとパーミッション一覧

コマンドは `/jecon` と `/money` の 2 系統でエイリアスされている。以下は代表的なもの。

| コマンド | パーミッション | デフォルト | 説明 |
| --- | --- | --- | --- |
| `/money`、`/money show` | `jecon.show` | 全員 | 自分の残高を表示 |
| `/money show <player>` | `jecon.show.other` | OP | 他プレイヤーの残高を表示 |
| `/money pay <player> <amount>` | `jecon.pay` | 全員 | 他プレイヤーへ送金 |
| `/money set <player> <balance>` | `jecon.set` | OP | 残高を絶対値で設定 |
| `/money give <player> <amount>` | `jecon.give` | OP | 残高を加算 |
| `/money take <player> <amount>` | `jecon.take` | OP | 残高を減算 |
| `/money create <player> [balance]` | `jecon.create` | OP | 口座を作成 |
| `/money remove <player>` | `jecon.remove` | OP | 口座を削除 |
| `/money top [page]` | `jecon.top` | OP | 残高ランキング |
| `/money convert` / `/money convert confirm` | `jecon.convert` | OP | DB 変換（SQLite ⇔ MySQL） |
| `/money reload` | `jecon.reload` | OP | 設定再読込 |
| `/money version` | `jecon.version` | OP | バージョン表示 |
| `/jecon account create <alias> [initial]` | `jecon.account` + `jecon.account.namespace.<ns>.create` | OP | 非プレイヤー口座を作成 |
| `/jecon account list <namespace>` | `jecon.account` | OP | 指定 namespace の口座を一覧 |
| `/jecon account send <from> <to> <amount>` | `jecon.account` + `jecon.account.namespace.<ns>.transfer` | OP | 口座間送金（alias または UUID 指定） |

### パーミッションのまとめ

以下のグループパーミッションが定義されている。

- `jecon.*`
  すべての権限を含む。
- `jecon.user`
  一般利用者向け（`jecon.show` + `jecon.pay`）。
- `jecon.op`
  管理者向け（管理系サブコマンド一式）。
- `jecon.transfer.overdraft`
  `/jecon account send` の実行時に、送金元残高不足でも強制的に引き落とす（overdraft）を許可する。デフォルト OP。
- `jecon.viewnonplayer`
  `hideNonPlayerAccounts: true` の設定下でも、`/money top` の結果や `<player>` タブ補完に非プレイヤー口座を含めて見られる。デフォルト OP。
- `jecon.account.namespace.<namespace>.create`
  指定 `<namespace>` に属する非プレイヤー口座の作成を許可する（例: `jecon.account.namespace.company.create`）。
- `jecon.account.namespace.<namespace>.transfer`
  指定 `<namespace>` の口座からの送金を許可する。

権限プラグイン（LuckPerms など）から個別に付与する。namespace ごとの `.create` / `.transfer` は運用に応じて経理担当ロールなどに割り当てる想定。

## 口座管理（プレイヤー）

### 残高を直接いじる

- `/money set <player> <balance>` — 残高を絶対値で置き換える。
- `/money give <player> <amount>` — 残高を加算する。
- `/money take <player> <amount>` — 残高を減算する。

いずれも「口座がある」ことが前提。口座がない場合は `Account not found: <name>` になる。

### 口座を作る / 削除する

- `/money create <player> [balance]` — 口座を作る。`[balance]` を省略すると `defaultBalance`（config.yml）が入る。
- `/money remove <player>` — 口座を削除する。削除時の残高が完了メッセージに出るので、事前バックアップとして記録に残せる。

通常はプレイヤーの初回ログイン時に `createAccountOnJoin: true` によって自動作成される。`create` は例外対応用。

## 非プレイヤー口座（`/jecon account ...`）

Jecon は「プレイヤー UUID を持たない口座」（システム口座・法人口座・イベントプールなど）を一級市民として扱う。
これは Job / Shop / Stock / Event 系プラグインとの連携ハブとして重要。

### alias の書式

非プレイヤー口座は `<namespace>:<key>` の 1 文字列で識別する。

- 使える文字: `[a-z0-9_-]`
- `namespace`: 1〜32 文字
- `key`: 1〜64 文字
- `:` は 1 個だけ
- 大文字は保存時に小文字化される

慣習的な namespace は以下（[spec/02-account-model.md](../spec/02-account-model.md) より）。

- `system:*` — Economy 本体の対向口座（税、ブリッジ、mint/burn など）
- `company:*` — 法人口座
- `pool:*` — イベント全体の報酬プール
- `house:*` — カジノハウス

`system:legacy_source` / `system:legacy_sink` / `system:vault_bridge` / `system:vault_unlocked_bridge` の 4 つはプラグイン起動時に自動作成される（Vault 互換ブリッジ用）。

### `/jecon account create`

```
/jecon account create <alias> [initial]
```

- `<alias>`: `<namespace>:<key>` 形式。
- `[initial]`: 初期残高（省略時は 0）。

実行には `jecon.account` に加えて `jecon.account.namespace.<namespace>.create` が必要。例:

```
/jecon account create company:acme 10000
/jecon account create pool:event_2026
/jecon account create system:tax_sink
```

### `/jecon account list`

```
/jecon account list <namespace>
```

指定した namespace に属する口座を、最大 20 件まで（残高付き）で表示する。

### `/jecon account send`

```
/jecon account send <from> <to> <amount>
```

- `<from>` / `<to>` には alias（`system:tax_sink` など）または UUID を指定できる。
- 送金元 alias が namespace を持つ場合、`jecon.account.namespace.<namespace>.transfer` が必要。
- `jecon.transfer.overdraft` を持っていれば、残高不足でも強制的に引き落とせる（overdraft コンテキストで走る）。

エラー時のレスポンスは以下のいずれか。

- `Insufficient funds in <from> (available=..., required=...)` — 残高不足
- `Vetoed by modifier '<id>': <reason>` — Modifier パイプラインによる拒否
- `Account missing: <SOURCE|DESTINATION>` — 口座が消えている
- `Invalid amount: <reason>` — 金額不正

成功時は `Sent <amount> from <from> to <to> (id=<transfer-id>)`。`id` は監査ログ (`transaction_log` テーブル) の主キー。

## 設定ファイル

### `config.yml`

主な項目:

```yaml
defaultBalance: 10000.0        # 初回口座作成時のデフォルト残高
createAccountOnJoin: true      # ログイン時に自動で口座を作るか
locale: en                     # メッセージロケール (en / ja)。message_<locale>.yml を読む
transactionLog: true           # transaction_log テーブルに全変動を記録するか
hideNonPlayerAccounts: true    # /money top と <player> 補完で非プレイヤー口座を隠すか
```

#### `format` — 通貨表示のカスタマイズ

```yaml
format:
  singularMajor: "dollar"
  pluralMajor: "dollars"
  singularMinor: "cent"
  pluralMinor: "cents"
  format: "{major} {majorcurrency} {minor} {minorcurrency}"
  # formatZeroMinor: "{major} {majorcurrency}"   # 小数部が 0 のときだけ別書式を使う（任意）
  minorType: asis    # omit / accurate / asis
```

`minorType` の違い:

- `omit`: `1.02 → 1.02`、`3.40 → 3.4`（末尾 0 を落とす）
- `accurate`: `1.02 → 1.02`、`3.40 → 3.40`（常に 2 桁）
- `asis`: `1.02 → 1.2`、`3.40 → 3.40`（そのまま。テキスト表記向け）

サーバーで採用する通貨単位に合わせて調整する。日本円のように「小数を持たない通貨」で運用したい場合は、
`format: "{major}円"` のように小数を省いた書式にし、`formatZeroMinor` で 0 の場合を明示するとよい。

#### `database`

```yaml
database:
  type: sqlite            # sqlite / mysql
  sqlite:
    file: "jecon.db"
  mysql:
    host: "localhost:3306"
    name: "jecon"
    username: "root"
    password: "..."
    init: "SET SESSION query_cache_type=0"
    properties:
      useSSL: "false"
      # ...
  connectionPool:
    maximumPoolSize: -1   # -1 は HikariCP のデフォルトを使う
    minimumIdle: -1
    maxLifetime: -1
    connectionTimeout: -1
    idleTimeout: -1
```

MySQL の場合、`init` はコネクション取得時に流す SQL。既定は `SET SESSION query_cache_type=0`。

### `message.yml` / `message_ja.yml` / `message_en.yml`

MiniMessage 形式で出力メッセージを定義する。

- `locale: en` → `message_en.yml`（同梱）を読む。
- `locale: ja` → `message_ja.yml`（同梱）を読む。
- `message.yml` を手動で置いた場合はそちらが優先される（レガシーな導入向け）。

MiniMessage 記法の詳細は [minimessage.md](../../minimessage.md) と [MiniMessage 公式ドキュメント](https://docs.advntr.dev/minimessage/format.html) を参照。

### 設定の反映

- `/money reload` — 設定を即座に読み直す（内部的には `onDisable` → `onEnable` を走らせる）。DB 接続も張り直す。
- サーバー全体の再起動でも当然反映される。

## データベース

Jecon は HikariCP でコネクションプールを張り、SQLite または MySQL を使う。

### スキーマの概要

- `account(id, uuid, alias, is_player, namespace, created_at)` — 口座のマスタ
- `balance(id, balance)` — セント単位の残高（`balance` は `long`。値 = 金額 × 100）
- `transaction_log(...)` — 監査ログ（`transactionLog: true` 時）

詳細スキーマは [spec/07-persistence.md](../spec/07-persistence.md)。

### SQLite ⇔ MySQL の乗り換え — `/money convert`

DB 種類を切り替えたいときの手順:

1. **必ずバックアップを取る**（変換先 DB の既存データはすべて消える）。
2. `config.yml` を編集し、切り替え **先** の DB 設定（`sqlite.file` または `mysql.*`）を正しく書く。`database.type` は変えない（現行値のままにする）。
3. `/money convert` を実行する。変換元と変換先の設定サマリが表示される。
4. 内容を確認し、`/money convert confirm` を実行する。
5. 変換が終わると自動で `onDisable` → `onEnable` が走り、新 DB での運用に切り替わる。

`/money convert` は「現在の DB → 反対側の DB」への一方向のマイグレーションで、実行後 `database.type` が自動で書き換わる。

### バックアップの考え方

- SQLite: `plugins/Jecon/jecon.db` をサーバー停止中にコピーするのが確実。稼働中コピーは inconsistent になる可能性がある。
- MySQL: `mysqldump` 等の標準手段で。テーブル `account`, `balance`, `transaction_log` が対象。

## Vault / VaultUnlocked 連携

- **Vault (1.x)**: `net.milkbowl.vault.economy.Economy` を実装した `VaultEconomy` を `ServicesManager` に登録する。ロード順で Vault が後になっても、`PluginEnableEvent` で拾ってフックする。
- **VaultUnlocked (2.x)**: `net.milkbowl.vault2.economy.Economy` を実装した `VaultUnlockedEconomy` を登録。VaultUnlocked が入っていれば起動時に自動でフック。

両ブリッジとも、内部の `TransferService` に委譲して同一の Modifier パイプラインを通す。
つまり、Job プラグインが Vault 経由で報酬を支給する場合も、税・cap・監査ログの適用対象になる。

### システム対向口座

- `system:vault_bridge` — Vault (1.x) 経由の出入りに使う対向口座
- `system:vault_unlocked_bridge` — VaultUnlocked 経由の対向口座
- `system:legacy_source` / `system:legacy_sink` — 旧 API 経由の入出金の対向

これらはプラグイン起動時に自動作成される。手動で削除しないこと。

## 監査・トラブルシューティング

### 送金ログ (`transaction_log`)

`transactionLog: true` の場合、すべての残高変動が `transaction_log` に記録される。
記録には transfer id、送金元・送金先の UUID、金額、source（例: `admin`、`vault`、`command`）、metadata、時刻などが含まれる。

`/jecon account send` の完了メッセージに出る `id=<n>` はこのテーブルの主キー。障害調査時に突き合わせできる。

詳細フィールドは [spec/04-context-and-log.md](../spec/04-context-and-log.md) を参照。

### よくある問題

- **Vault にフックされない**
  Vault プラグインが有効になっているか確認。`/plugins` で Vault が緑になっているか、`/money version` でハンドリング状況が出るか。
  ログに `Hooked Vault` / `Hooked VaultUnlocked` の 1 行が出ていれば OK。
- **`/money top` に非プレイヤー口座が並んで困る**
  `hideNonPlayerAccounts: true`（デフォルト）にしておくと、通常プレイヤーには非プレイヤー口座が見えなくなる。
  管理者側は `jecon.viewnonplayer` を持たせておけば、`true` のままで自分だけ見られる。
- **`Vetoed by modifier '<id>': <reason>` が頻発する**
  Modifier（税、cap、日次リミットなど）による拒否。導入している経済系プラグイン側の設定を見直す。
- **DB 変換後にプラグインが起動しない**
  `config.yml` の変換先設定が誤っている可能性が高い。SQLite ならファイルパス、MySQL なら `host`, `name`, `username`, `password` を再確認する。
- **残高が 2 桁小数以上入っている？**
  内部は long（セント）で 2 桁固定。API から `BigDecimal` を渡しても、`setScale(2, HALF_UP)` で丸めた上で保存される。

### ログの活用

- コンソールに `[Jecon]` プレフィクスで出るログはプラグイン本体のもの。
- HikariCP の警告（コネクションプール枯渇、slow query など）は Jecon 経由ではあるが原因は DB 側であることが多い。
- 起動時に `plugin.yml` の権限一覧が Bukkit にロードされていない場合、権限設定がまるごと効かない。JAR が破損していないか確認する。

## 参考リンク

- 開発者向け仕様書: [../spec/README.md](../spec/README.md)
- Vault ブリッジの詳細: [../spec/08-vault-bridge.md](../spec/08-vault-bridge.md)
- 公開 API: [../spec/06-public-api.md](../spec/06-public-api.md)
- MiniMessage 早見表: [../../minimessage.md](../../minimessage.md)
