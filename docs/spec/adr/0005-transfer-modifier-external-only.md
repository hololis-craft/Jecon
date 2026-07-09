# ADR-0005 Modifier は外部登録のみで、Economy 本体に built-in を持たない

## ステータス

受け入れ

## 背景

税、日次キャップ、cap_c 回路ブレーカーなど「振替を条件付きで書き換える」機能をどこに置くかの選択肢がある。

### 案 A：Economy 本体に built-in

Economy が税率テーブルや cap_c パラメータを持ち、config.yml で有効化する。
呼び出し側は何もしなくても税や cap が効く。

### 案 B：外部 Modifier で登録

Economy は `TransferModifier` インタフェースだけを提供する。
税・日次キャップ・cap_c はすべて外部プラグインが Modifier を登録して差し込む。

## 決定

案 B を採る。Economy 本体に built-in Modifier は持たない。

- Economy は `ModifierRegistry` を公開するだけ。
- 税、日次キャップ、cap_c 回路ブレーカーはイベント固有プラグインが Modifier として登録する。
- Modifier の優先度順序は登録側の責務（[05-modifier-pipeline.md](../05-modifier-pipeline.md)）。

Job プラグインの `JobRewardModifier` と同じ形式で、外部プラグインが自由に登録・解除できる。

## 結果

- Economy 本体はイベント特有のルール（cap_c、税率、日次キャップの数値）を知らずに済む。
  他イベントや他サーバに Economy を持ち込むときに、余分な機能を捨てる必要がない。
- 税・日次キャップ・cap_c の実装がイベント固有プラグイン側に集約される。
  イベント終了後に該当プラグインを外せば、Economy は素の状態に戻る。
- 一方で、シンプルな税を欲しいだけの利用者にとってはコストが高い。
  Economy 本体に built-in 税を用意しない代わりに、リファレンス実装として `SalesTaxModifier` をサンプルドキュメントで示す（[05-modifier-pipeline.md](../05-modifier-pipeline.md)）。
- cap_c 回路ブレーカーは総流通量を state として持つ必要がある。
  Modifier 側で state 管理を書くのは負担だが、Modifier に `JeconTransferCompletedEvent` を購読させれば post-hoc に集計できる（[05-modifier-pipeline.md](../05-modifier-pipeline.md)）。

## 選択しなかった代替案

- **案 A（built-in）**：本イベントで固有のパラメータ（cap_c の閾値式、税率のドメイン依存性）を Economy 本体に持ち込むと、Economy が「イベント専用プラグイン」に堕する。
- **built-in と外部の混在**（Economy に built-in 税、外部で cap_c）：どちらのレイヤに書くべきかの判断がドキュメントで曖昧になる。すべて外部に統一するほうが説明しやすい。

## 関連

- [05-modifier-pipeline.md](../05-modifier-pipeline.md)
