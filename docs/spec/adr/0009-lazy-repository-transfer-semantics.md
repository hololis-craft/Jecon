# ADR-0009 Lazy モード下の transfer 意味論

## ステータス

置き換え（[ADR-0012](./0012-drop-lazy-repository.md) により LazyRepository 自体を廃止した）

## 背景

Jecon の `LazyRepository` は次の設計で multi-server safe を実現している。

- プレイヤーログイン時に DB から残高を読んで cache に載せる。
- 稼働中は cache 上で `deposit` / `withdraw` を反映する。
- ログアウト時 / shutdown 時、cache の値と初期ロード値の差分（delta）を DB に `UPDATE balance SET balance = balance + ?` として書き込む。

delta 書き込みなので、別サーバ（BungeeCord 配下の別 Paper）で残高が変わっていても壊れない。

しかし本仕様の `transfer` は「2 口座を跨いだ原子的振替」を要求する。
Lazy モードでこれをどう実現するかに設計論点がある。

### 案 A：cache のみを更新して、ログアウト時に flush

`transfer` は cache 上で from -= amount、to += amount するだけ。
DB への delta 書き込みは既存のログアウト時 flush に任せる。

問題：

- shutdown 前にクラッシュすると全 transfer が失われる。
- 監査ログの INSERT は即時に発行するとして、実残高が cache のみでは監査と現物が乖離する。
- 別サーバから残高を見ると古い値が見える時間が長くなる。

### 案 B：cache 更新 + DB への即時 delta 書き込み

`transfer` は cache 更新の直後、DB にも delta 書き込みを 1 トランザクションで発行する。
既存のログアウト時 flush 経路とは別の I/O パスで扱う。

利点：

- クラッシュ耐性が高い。DB は常に真実に近い。
- 別サーバから見て「振替が完了した瞬間」に反映される。
- 監査ログと実残高の一貫性が保てる。

欠点：

- `transfer` ごとに DB round trip が発生する。
- `LazyRepository` の「まとめて書く」利点を transfer では享受できない。

### 案 C：Lazy モードでは `transfer` を実質 Sync として振る舞う

`transfer` の hot path だけ Sync 相当にする。
`deposit` / `withdraw` の従来経路（後方互換シム経由）も transfer に集約されるので、事実上 Lazy write-back の適用範囲が消える。

## 決定

案 B を採る。

- `transfer` は cache 更新をメインスレッド原子的に行う。
- 続けて DB への delta 書き込みを 1 トランザクションで発行する。
  同期でも非同期でもよいが、順序は保証する（FIFO ワーカ）。
- 監査ログ `INSERT` は同じトランザクションに含める。
- 既存の Lazy write-back 経路（ログアウト時 flush）は、cache と DB の差分が 0 のときに no-op として済むように整合する。

これにより、Lazy モードの delta 書き込みが持つ multi-server safe（別サーバの残高変更を上書きしない）は維持しつつ、transfer の原子性を確保する。

### 具体的な流れ

```
transfer(from, to, amount, ctx) {
  main thread:
    cache[from] -= amount
    cache[to] += amount
    enqueue({from, to, amount, ctx})   // ordered queue

  I/O worker (single, FIFO):
    for each queued transfer:
      BEGIN
      UPDATE balance SET balance = balance - ? WHERE id = ?_from
      UPDATE balance SET balance = balance + ? WHERE id = ?_to
      INSERT INTO transaction_log ...
      COMMIT

  Event dispatcher (main thread):
    fire JeconTransferCompletedEvent
}
```

I/O worker は 1 本のスレッドで、キューを FIFO 順に消化する。
複数レッグ（`transferBatch`）は全 leg を 1 トランザクションに含める。

### クラッシュ耐性

cache 更新後、DB 書き込み前にクラッシュすると当該 transfer が失われる。
これを短く保つため、I/O worker はキューを即時消化する（1 秒バッチではない）。

複数の transfer が同時にキューに積まれた場合、順序が保存されるので、後続の transfer が「先の transfer が反映された残高」に依存していれば矛盾なく処理される。

### 監査ログの整合

監査ログの INSERT は残高更新と同一トランザクションに入れる。
これにより「残高は動いたが監査ログにない」「監査ログにあるが残高が動いていない」のいずれも起きない。

Job プラグインの `action_log`（[Job spec 05-persistence.md](../../../Jobs/spec/05-persistence.md)）は 1 秒バッチだが、Economy の `transaction_log` は同期。
役割が違う（`action_log` は 1 アクション 1 行、`transaction_log` は 1 振替 1 行）ため、バッチ粒度も異なる。

## 結果

- Lazy モードでも transfer の原子性と一貫性が確保される。
- クラッシュで失う transfer は「キューに乗ってから DB 書き込み前」の極短時間のみ。
- multi-server safe（delta 書き込み）は維持される。
- I/O 頻度が上がる。Vault 経由呼び出しが多いと DB 負荷が増える。
  hot account の row lock 競合は運用で計測する（[03-transfer-api.md](../03-transfer-api.md)）。
- 既存 Jecon の `LazyRepository` の flush ロジックは維持したまま、transfer 専用のパスを併走させる形になる。

## 選択しなかった代替案

- **案 A（cache のみ）**：クラッシュ耐性と監査整合の両面で劣る。イベントの重要イベント（配当、税徴収）を確実に永続化したい要件と合わない。
- **案 C（Lazy を実質廃止）**：ログイン時のバースト書き込みや、hot path でのキャッシュヒット効率など、Jecon の Lazy モードの利点を捨ててしまう。`transfer` 以外の read 系（`getBalance`）ではキャッシュを引き続き使いたい。

## 関連

- [03-transfer-api.md](../03-transfer-api.md)
- [07-persistence.md](../07-persistence.md)
