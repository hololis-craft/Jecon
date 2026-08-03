# ADR-0014 書き込み経路をスレッドセーフにし、任意のスレッドから呼べるようにする

## ステータス

受け入れ（[ADR-0012](./0012-drop-lazy-repository.md) のスレッドモデル注記を置き換える）

## 背景

[06-public-api.md](../06-public-api.md) の旧スレッドモデルは、`TransactionQueryService` を除く全 Service を
「メインスレッドのみ」と宣言していた。しかしこれは Vault 経路では守らせることができない。

`net.milkbowl.vault.economy.Economy` は同期インタフェースであり、呼び出し元プラグインは Jecon の
ドキュメントを読まない。ショップ、job、報酬系のプラグインが async スレッドから `depositPlayer` を
呼ぶことは実際にあり、Jecon 側でそれを禁止する手段はない（インタフェースに async 版が無いため、
呼び出し元がスレッドを選ぶ）。

そして当時の実装は、その呼び出しに対して**最悪の壊れ方**をしていた。

1. `TransferServiceImpl` は DB トランザクションを commit した**後**に `JeconTransferCompletedEvent` を
   直接 `callEvent` していた。
2. Bukkit は同期 event を非メインスレッドから発火すると `IllegalStateException` を投げる。
3. 結果として「残高は動き、監査ログにも記録され、しかし呼び出し元には例外が返る」状態になる。
4. Vault 呼び出し元は `EconomyResponse` を受け取れないので失敗と判断し、補償やリトライを行う。
   → **二重付与 / 二重引き落とし**。

さらに厄介なことに、失敗する経路（金額 0、口座なし、残高不足、Veto）は event を発火しないので
正常に返る。つまり「失敗するときだけ成功する」挙動だった。

## 検討した選択肢

### 案 A: メインスレッド専用を維持し、非メインスレッドからの書き込みを早期に拒否する

DB に触る前に `isPrimaryThread()` を検査して例外にする。金は絶対に動かないので二重付与は
構造的に起きず、宣言と実装が一致する。

しかし Vault 呼び出し元から見れば「Jecon は async から使えない」だけであり、既に async から
呼んでいるプラグインは全部動かなくなる。async 対応を進めるという方針とは両立しない。

### 案 B: 書き込みを単一の writer スレッドに直列化する

全ての書き込みを 1 本のスレッドに流せば、JVM 内の read-modify-write 競合は消える。

**却下。** MySQL 構成では BungeeCord 配下の複数 Paper が同じ DB を叩くため、JVM 内で直列化しても
他サーバからの並行書き込みは止まらない。既存コードが `SELECT ... FOR UPDATE` と
`account.id` 昇順ロックを持っているのはまさにそのためで、単一 writer スレッドは
「JVM 内では起きない」ように見せかけるだけの偽の安心になる。マルチサーバでは壊れたままなので、
むしろ有害。

加えて、メインスレッドからの同期呼び出しが writer キューの後ろに並ぶことになり、
レイテンシの性質が変わる。

### 案 C: 全ての書き込み経路を真にトランザクショナルにする（採用）

DB レベルで原子性を確保すれば、スレッド数もサーバ数も問わず正しい。案 B が必要とする
仮定（writer が 1 つ）を置かないので、マルチサーバでもそのまま成立する。

## 決定

**公開 API は全て任意のスレッドから呼べる**ものとし、そのために以下を行う。

### 1. `Database` を 2 層にする

全メソッドに `Connection` を受け取るプリミティブ版を用意し、引数なし版はそれを 1 接続で
包むだけの wrapper にする。これにより上位が複数ステップを 1 トランザクションに畳める。

値を返す `inTransaction` と、一時的競合を再試行する `inTransactionWithRetry` を持つ。
並行書き込みを許す以上、InnoDB の deadlock / lock wait timeout と `SQLITE_BUSY` は
異常系ではなく正常系の一部なので、書き込み経路は原則 retry 付きを使う。

再試行するため、**トランザクションの中に DB 以外の副作用（event 発火、ログ、外部通知）を
置いてはいけない**。

### 2. ロック地点を `account` 行に統一する

口座に対する一連の操作（残高更新、メンバー権限更新、削除）は `account` 行のロック下で行う。
`account` 行は必ず存在するので、MySQL でも gap lock ではなく素の行ロックになる。

`account_member` のような「存在しないかもしれない行」を `FOR UPDATE` すると InnoDB は
gap lock を取るが、gap lock 同士は競合しないため、2 つのトランザクションが揃って
INSERT に進んでデッドロックする。これを避けるための選択。

複数口座に触る場合は `account.id` 昇順でロックする。削除と振替が同じ順序を使うので交錯しない。

### 3. `balance` 行を勝手に生やさない

`setBalanceInTx` は UPDATE が 0 件なら false を返す。以前は INSERT で自動作成していたため、
口座削除と並行した振替が `account` 行の無い `balance` 行を復活させていた（孤児行）。

`balance` 行の存在は「経済アカウントを持っている」ことを意味する（`hasAccount` の定義）ので、
生成は明示的な口座作成経路に限定する。

なお「全ての `account` 行が `balance` 行を持つ」という 1:1 不変条件は**採らない**。
`getOrCreatePlayerId` は UUID↔id の対応を作る primitive であり、これが `balance` 行も作ると
一度ログインした全プレイヤーで `hasAccount` が true になって `createAccountOnJoin` の意味が壊れる。

### 4. 絶対値の設定は楽観的並行制御にする

「現在の残高を読む → 差分を求める → 適用する」は、そのままではトランザクション外の
read-modify-write になる。`TransferService.setBalance` は読んだ残高をトランザクション内で
ロック取得後に検証し、動いていれば **Modifier pipeline を含めて**やり直す。

pipeline も含めてやり直すのは、modifier が金額を見て clamp や leg 追加を行うため。
古い残高から求めた金額を見せてはいけない。

上限まで競合したら `TransferResult.Conflict` を返す。残高は変更されていない。

### 5. SQLite は `BEGIN IMMEDIATE` で開始する

`setAutoCommit(false)` は deferred BEGIN になり、SELECT で read lock を取ってから
UPDATE で write lock に昇格する形になる。SQLite はこの昇格に対して busy handler を呼ばず
即 `SQLITE_BUSY` を返す（待つとデッドロックし得るため）ので、`busy_timeout` では救えない。

最初から write lock を取れば writer 同士の待ち合わせで解決できる。あわせて
`journal_mode=WAL`（reader が writer をブロックしない）と `busy_timeout` の明示設定を行う。

計測: 既定設定で 8 スレッド × 60 振替 → 480 トランザクション中の `SQLITE_BUSY` が 16 件 → 0 件。

### 6. event はキューに積んでメインスレッドから commit 順に流す

`QueuedEventDispatcher` に post し、毎 tick メインスレッドから drain する。

**メインスレッドからの post も即時発火せずキューを通す。** そうしないと、先に commit された
非同期の振替より後に commit された同期の振替の event が先に届き、発火順と commit 順がずれる。

代償として、event は `transfer()` の戻りより後・次の tick 以降になる。
「commit 直後の同期発火」という以前の性質は失われる。

`onDisable` では tick task が使えないので明示的に drain する。`onDisable` 自体は
メインスレッドで走るため Bukkit のスレッドチェックを通る。

### 7. `TransferModifier` にスレッド契約を持たせる

modifier はサードパーティのコードで、Bukkit API を触る可能性がある。
`isThreadSafe()`（既定 `false`）を追加し、`false` の modifier が登録されている状態で
非メインスレッドから振替が来た場合は、pipeline の実行だけをメインスレッドへ回す。

メインスレッドに回せなかった場合は、modifier を飛ばして通すのではなく振替を拒否する
（`Vetoed`, `modifierId` = `jecon:main-thread-bridge`）。veto や clamp を行うはずだった
modifier が、サーバが混んでいたという理由でバイパスされてはならない。

### 8. デッドロックしない構造を不変条件として置く

- 同期 API は呼び出し元のスレッド上でそのまま実行する（executor に投げて待たない）。
- したがって「メインスレッド → ワーカー」の待ちは存在しない。
- Jecon が持つ唯一のスレッド間待ちは「ワーカー → メインスレッド」（modifier hop）なので循環しない。

## 帰結

### 得たもの

- Vault / VaultUnlocked / `BalanceRepository` / `TransferService` を任意のスレッドから
  同期呼び出しできる。二重付与の原因だった「commit 後の例外」が消える。
- lost update（残高、権限ビット）と孤児行が構造的に起きなくなる。
- 一時的競合は自動で再試行される。

### 失ったもの / 受け入れたコスト

- **event が commit 直後に発火しなくなった。** 最大 1 tick 以上遅れ、`transfer()` の戻りより後になる。
  順序保証と引き換えの意図的な変更。
- **メインスレッドからの同期呼び出しは、並行する書き込みの行ロックを待つことがある。**
  以前はメインスレッドが唯一の writer だったので競合しなかった。DB レイテンシが tick に乗る。
  緩和策はロック保持時間を短く保つことと、呼び出し側が async に逃がすこと。
- **非 thread-safe な modifier があると、非メインスレッドからの振替に 1 tick の hop が乗る。**
- `TransferResult` に `Conflict` が増えたため、`switch` している呼び出し側は追随が必要
  （sealed なのでコンパイルエラーで検出される）。

### 残件

- `TransferService.transferAsync`（`CompletableFuture` 版）。同期 API が任意のスレッドから
  安全になったので、これは「メインスレッドを待たせない」ための利便性 API であり、
  正しさのために必要なものではない。追加する場合は executor のスレッド数を
  Hikari の `maximumPoolSize` 以下にすること（超えると connection 待ちで滞留する）。
- 単一 leg 振替の `batch_id` 後付け UPDATE の削減。ロック保持時間を縮められるが、
  `batch_id` は `TransactionRow` として公開されているので観測可能な変更になる。

## 検証

Bukkit サーバを起動しない JUnit テストで検証する（`EventDispatcher` と `MainThreadBridge` を
挟んだのはこのため）。競合系のテストは、修正前のコードに対して実際に落ちることを確認してある。

| テスト | 検出する不具合 | 修正前の結果 |
|---|---|---|
| `AbstractRepositoryFormatTest.formatIsThreadSafe` | 共有 `NumberFormat` / `HashMap` | `1 dollar 1 cent` が `1 dollar 3 cents` になる |
| `DatabaseConcurrencyTest.concurrentTransfersConserveTotalBalance` | 残高の lost update | 総額が 600000 → 604490 |
| `AccountServiceImplTest.concurrentSetPermissionDoesNotLoseBits` | 権限ビットの lost update | ビットが落ちる |
| `AccountServiceImplTest.concurrentDeleteAndBalanceWriteLeavesNoOrphanRows` | 孤児 `balance` 行 | 60 行残る |
| `TransferServiceImplTest.setBalanceHitsItsTargetEvenIfTheBalanceMovesAfterTheRead` | `set` の stale read | 200.00 を指定して 210.00 になる |

最後のテストは Modifier pipeline を同期フックとして使う決定論的なテスト。pipeline は残高読み取りの
後・トランザクションの前に走るので、そこで別スレッドの入金を確定させれば狙った窓に必ず割り込める。
最終残高だけを見る素朴な並行テストでは検出できない（210.00 は「入金が set の後に確定した」場合の
正しい結果でもあるため）。実際に、先に書いたタイミング依存版は修正前のコードでも通ってしまった。

## 関連

- [ADR-0012 LazyRepository を廃止し Sync 単一モードにする](./0012-drop-lazy-repository.md)
- [ADR-0011 VaultUnlockedAPI 採用と shared account 写像](./0011-vaultunlocked-shared-account-no-async.md)
- [06-public-api.md スレッドモデル](../06-public-api.md)
