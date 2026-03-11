# Adventure Text API & Text Serializer リファレンス

> **対象:** PaperMC / Velocity プラグイン開発者向け
> **ライブラリ:** Adventure (net.kyori.adventure) — Paper・Velocityにネイティブ同梱
> **情報源:** <https://docs.papermc.io/adventure/text/> , <https://docs.papermc.io/paper/dev/component-api/introduction/>

---

## 1. 概要：なぜComponentを使うべきか

Minecraft 1.7以降、テキスト表示には **Component** が使われている。旧来の `§c` や `&6` といったレガシーフォーマットは将来的に廃止予定であり（Mojang公式声明済み）、Componentへの移行が強く推奨される。

### レガシー形式 vs Component

| 項目 | レガシー (`§` / `&`) | Component (Adventure) |
|------|---------------------|----------------------|
| 構造 | 線形（フラットな文字列） | ツリー構造（親→子のスタイル継承） |
| 色 | 16色のみ | **任意のRGB色** (`#RRGGBB`) |
| 装飾 | 基本装飾のみ | 太字・斜体・下線・取消線・難読化 |
| クリックイベント | ✕ 不可 | ✓ URL表示・コマンド実行・クリップボードコピー等 |
| ホバーイベント | ✕ 不可 | ✓ テキスト表示・アイテム表示・エンティティ表示 |
| コンポーネント種別 | テキストのみ | テキスト・翻訳可能・キーバインド・スコア等 |
| 将来性 | **廃止予定** | Minecraft公式サポート |

---

## 2. Component の作成

Adventure の `Component` はすべて **不変 (immutable)** である。変更操作は常に新しいインスタンスを返す。

### 2.1 基本的なテキストコンポーネント

```java
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

// 方法1: ファクトリメソッドチェーン（簡潔だが変更のたびにインスタンスが生成される）
final Component component = Component.text("Hello")
    .color(TextColor.color(0x13f832))
    .append(Component.text(" world!", NamedTextColor.GREEN));

// 方法2: ビルダー（推奨：複雑なコンポーネントに最適）
final TextComponent component = Component.text()
    .content("Hello")
    .color(TextColor.color(0x13f832))
    .append(Component.text(" world!", NamedTextColor.GREEN))
    .build();
```

### 2.2 実践的な例：装飾付きメッセージ

```java
// "You're a Bunny! Press <key> to jump!" というメッセージ
final TextComponent textComponent = Component.text()
    .content("You're a ")
    .color(TextColor.color(0x443344))
    .append(Component.text().content("Bunny").color(NamedTextColor.LIGHT_PURPLE))
    .append(Component.text("! Press "))
    .append(
        Component.keybind().keybind("key.jump")
            .color(NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.BOLD, true)
            .build()
    )
    .append(Component.text(" to jump!"))
    .build();
```

### 2.3 static import を活用した簡潔な記述

```java
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.TextColor.color;
import static net.kyori.adventure.text.format.NamedTextColor.GREEN;

final Component component = text()
    .content("Hello").color(color(0x13f832))
    .append(text(" world!", GREEN))
    .build();
```

---

## 3. スタイル（Style）

Style は `TextColor` と `TextDecoration` のスーパーセットであり、コンポーネントに適用できる。

### 3.1 TextColor（テキスト色）

```java
// 任意のRGB色
TextColor.color(0x13f832)       // 16進数
TextColor.color(19, 248, 50)    // RGB個別指定

// 名前付き色（NamedTextColor: Minecraftの標準16色）
NamedTextColor.GOLD
NamedTextColor.AQUA
NamedTextColor.RED
NamedTextColor.LIGHT_PURPLE
// ... 他に BLACK, DARK_BLUE, DARK_GREEN, DARK_AQUA, DARK_RED,
//     DARK_PURPLE, GOLD, GRAY, DARK_GRAY, BLUE, GREEN, AQUA,
//     RED, LIGHT_PURPLE, YELLOW, WHITE
```

### 3.2 TextDecoration（テキスト装飾）

```java
TextDecoration.ITALIC        // 斜体
TextDecoration.BOLD          // 太字
TextDecoration.STRIKETHROUGH // 取消線
TextDecoration.UNDERLINED    // 下線
TextDecoration.OBFUSCATED    // 難読化（文字がランダムに変化するエフェクト）
```

装飾の適用:

```java
Component.text("Bold text")
    .decoration(TextDecoration.BOLD, true);

// 親から継承された装飾を明示的に無効化
Component.text("Not bold")
    .decoration(TextDecoration.BOLD, false);
```

---

## 4. イベント

### 4.1 ClickEvent（クリックイベント）

```java
import net.kyori.adventure.text.event.ClickEvent;

// URLを開く
Component.text("Click here")
    .clickEvent(ClickEvent.openUrl("https://example.com"));

// コマンドを実行
Component.text("Run command")
    .clickEvent(ClickEvent.runCommand("/say hello"));

// コマンドを提案（チャット入力欄に挿入）
Component.text("Suggest")
    .clickEvent(ClickEvent.suggestCommand("/tp "));

// クリップボードにコピー
Component.text("Copy")
    .clickEvent(ClickEvent.copyToClipboard("copied text"));

// 本のページ変更
Component.text("Next page")
    .clickEvent(ClickEvent.changePage(2));
```

### 4.2 HoverEvent（ホバーイベント）

```java
import net.kyori.adventure.text.event.HoverEvent;

// テキストを表示
Component.text("Hover me")
    .hoverEvent(HoverEvent.showText(Component.text("Tooltip!")));

// アイテムを表示
Component.text("Item")
    .hoverEvent(HoverEvent.showItem(/* ShowItem */));

// エンティティを表示
Component.text("Entity")
    .hoverEvent(HoverEvent.showEntity(/* ShowEntity */));
```

---

## 5. コンポーネントの種別

| 種別 | 説明 | 生成方法 |
|------|------|---------|
| `TextComponent` | 固定テキスト | `Component.text("Hello")` |
| `TranslatableComponent` | クライアント言語に翻訳 | `Component.translatable("block.minecraft.stone")` |
| `KeybindComponent` | クライアントのキー設定を表示 | `Component.keybind("key.jump")` |
| `ScoreComponent` | スコアボードの値を表示 | `Component.score("player", "objective")` |
| `SelectorComponent` | エンティティセレクタ | `Component.selector("@a")` |
| `BlockNBTComponent` | ブロックのNBTデータ | `Component.blockNBT()` |
| `EntityNBTComponent` | エンティティのNBTデータ | `Component.entityNBT()` |
| `StorageNBTComponent` | ストレージのNBTデータ | `Component.storageNBT()` |

---

## 6. Text Serializer（テキストシリアライザ）

Serializerは `Component` と各種文字列表現の相互変換を行う。

### 6.1 一覧と選定基準

| Serializer | フォーマット | ロスレス | 用途 |
|-----------|------------|---------|------|
| **MiniMessage** | `<gold>Hello <aqua><bold>world</bold></aqua>!` | ✓ | **ユーザ入力・設定ファイル（推奨）** |
| **JSON** (`JSONComponentSerializer`) | `{"text":"Hello","color":"gold",...}` | ✓ | プログラム間連携・DB保存 |
| **Gson** (`GsonComponentSerializer`) | JSON (Gson `JsonElement`対応) | ✓ | Gson連携が必要な場合 |
| **Plain** (`PlainTextComponentSerializer`) | `Hello world!` | ✕ (非常にlossy) | ログ出力・デバッグ |
| **Legacy** (`LegacyComponentSerializer`) | `§6Hello §b§lworld§c!` | ✕ (lossy) | **非推奨**: 旧形式の互換用のみ |
| **ANSI** (`ANSIComponentSerializer`) | ANSIエスケープコード | ✕ (encoderのみ) | ターミナル出力 |

### 6.2 シリアライズ（Component → 文字列）

```java
import net.kyori.adventure.text.serializer.json.JSONComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;

// テスト用コンポーネント
final TextComponent textComponent = Component.text()
    .content("Hello ")
    .color(NamedTextColor.GOLD)
    .append(Component.text("world", NamedTextColor.AQUA, TextDecoration.BOLD))
    .append(Component.text("!", NamedTextColor.RED))
    .build();

// JSON形式: Minecraft標準のJSONシリアライズ
String json = JSONComponentSerializer.json().serialize(textComponent);

// MiniMessage形式（ロスレス・ユーザフレンドリー）
String mini = MiniMessage.miniMessage().serialize(textComponent);

// レガシー形式: "&6Hello &b&lworld&c!"
String legacy = LegacyComponentSerializer.legacyAmpersand().serialize(textComponent);

// プレーンテキスト: "Hello world!" （スタイル情報はすべて消失）
String plain = PlainTextComponentSerializer.plainText().serialize(textComponent);
```

### 6.3 デシリアライズ（文字列 → Component）

```java
// JSONから
Component comp1 = JSONComponentSerializer.json().deserialize(json);

// MiniMessageから
Component comp2 = MiniMessage.miniMessage().deserialize("<gold>Hello <aqua><bold>world</bold></aqua><red>!");

// レガシー形式から（§記号版 / &記号版）
Component comp3 = LegacyComponentSerializer.legacySection().deserialize("§6Hello §b§lworld§c!");
Component comp4 = LegacyComponentSerializer.legacyAmpersand().deserialize("&6Hello &b&lworld&c!");

// プレーンテキストから
Component comp5 = PlainTextComponentSerializer.plainText().deserialize("Hello world!");
```

---

## 7. MiniMessage（推奨フォーマット）

MiniMessageは Adventure が提供する人間可読なコンポーネント記法で、**ロスレス**かつ**ユーザが編集しやすい**。設定ファイルやコマンド入力に最適。

### 7.1 基本的な使い方

```java
import net.kyori.adventure.text.minimessage.MiniMessage;

// デシリアライズ
Component comp = MiniMessage.miniMessage().deserialize("<blue>Hello <red>World!");

// ヘルパーメソッドの作成（推奨）
public static Component mm(String miniMessageString) {
    return MiniMessage.miniMessage().deserialize(miniMessageString);
}

// 使用例
Component comp = mm("<blue>Hello <red>World!");
```

### 7.2 主要なタグ一覧

```
色:          <red>, <blue>, <gold>, <#FF5555> (任意のHEX色)
装飾:        <bold>, <italic>, <underlined>, <strikethrough>, <obfuscated>
装飾無効化:  <!bold>, <!italic>
閉じタグ:    </bold>, </red>, </色名>
リセット:    <reset>
クリック:    <click:open_url:'https://example.com'>
           <click:run_command:'/say hi'>
           <click:suggest_command:'/tp '>
           <click:copy_to_clipboard:'text'>
ホバー:     <hover:show_text:'<red>Tooltip!'>
キーバインド: <key:key.jump>
翻訳:       <lang:block.minecraft.stone>
挿入:       <insertion:'text'>  (Shift+クリックでチャット入力欄に挿入)
レインボー:  <rainbow>text</rainbow>
グラデ:     <gradient:red:blue>text</gradient>
```

### 7.3 ツリー構造の継承を活用した例

```java
Component comp = MiniMessage.miniMessage().deserialize(
    "<#438df2><b>This is the parent component; its style is " +
    "applied to all children.\n<u><!b>This is the first child, " +
    "which is rendered after the parent</!b></u><key:key.inventory></b></#438df2>"
);
```

### 7.4 MiniMessage Webビューア

複雑なコンポーネントの構築とリアルタイムプレビューが可能:
**<https://webui.advntr.dev/>**

---

## 8. GsonComponentSerializer の詳細

Gson ライブラリを用いたJSON形式のシリアライズ／デシリアライズを行う。

### 8.1 バージョン別のシリアライザ

```java
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

// Minecraft 1.16+ 用（RGB色・新ホバーイベント形式対応）
GsonComponentSerializer serializer = GsonComponentSerializer.gson();

// 全バージョン互換（RGB色をNamedTextColorにダウンサンプル）
GsonComponentSerializer compat = GsonComponentSerializer.colorDownsamplingGson();
```

### 8.2 選定基準

- **設定ファイル・DBへの保存**: `GsonComponentSerializer.gson()`（デフォルト1.16形式）
- **クライアントへの送信**: プラットフォーム（Paper/Velocity）が自動処理する。直接使う場合はクライアントバージョンに応じて選択
- **デシリアライズ**: デフォルトのGson serializer（後方互換あり）
- **1.15.2以下のクライアント向けシリアライズ**: `colorDownsamplingGson()`

### 8.3 Gson JsonElement との連携

```java
import com.google.gson.JsonElement;

// Component → JsonElement
JsonElement element = GsonComponentSerializer.gson().serializeToTree(component);

// JsonElement → Component
Component comp = GsonComponentSerializer.gson().deserializeFromTree(element);
```

---

## 9. LegacyComponentSerializer（レガシー互換）

> **⚠ 非推奨**: レガシー形式は将来廃止予定。新規開発では MiniMessage を使用すること。

### 9.1 ビルトインシリアライザ

```java
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

// §記号版（クライアント表示に使用される形式）
LegacyComponentSerializer.legacySection()  // §6Hello §bworld

// &記号版（設定ファイルやコマンドで使用される形式）
LegacyComponentSerializer.legacyAmpersand()  // &6Hello &bworld
```

### 9.2 制限事項

- ホバーイベント・クリックイベントは**シリアライズ不可**
- テキストコンポーネント以外（翻訳・キーバインド等）は**非対応**
- insertion は**非対応**
- RGB色はデフォルトでダウンサンプルされる（ビルダーで変更可能）

### 9.3 レガシーからMiniMessageへのマイグレーション

```java
// レガシー形式 → MiniMessage形式への変換
final String legacyString = ChatColor.RED + "This is a legacy " + ChatColor.GOLD + "string";

final String miniMessageString = MiniMessage.miniMessage().serialize(
    LegacyComponentSerializer.legacySection().deserialize(legacyString)
);
```

---

## 10. PlainTextComponentSerializer

```java
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

// Component → プレーンテキスト（スタイル情報はすべて消失）
String plain = PlainTextComponentSerializer.plainText().serialize(component);

// プレーンテキスト → Component（装飾なし）
Component comp = PlainTextComponentSerializer.plainText().deserialize("Hello world!");
```

用途: ログファイルへの出力、フォーマット除去、テキスト内容の比較など。

---

## 11. ANSIComponentSerializer（エンコーダ）

ANSIシリアライザは **エンコーダ** であり、シリアライズ（Component → ANSI文字列）のみ可能。デシリアライズは不可。

### 11.1 依存関係の追加

```kotlin
// build.gradle.kts
dependencies {
    implementation("net.kyori:adventure-text-serializer-ansi:4.26.1")
}
```

### 11.2 使い方

```java
import net.kyori.adventure.text.serializer.ansi.ANSIComponentSerializer;

String ansi = ANSIComponentSerializer.ansi().serialize(component);
```

### 11.3 カラーレベル設定

| 値 | 説明 |
|----|------|
| `none` | ANSIエスケープ無効 |
| `indexed16` | 元祖16色 |
| `indexed256` | 256色 |
| `truecolor` | 24bitフルカラー |

JVM引数: `-Dnet.kyori.ansi.colorLevel=truecolor`

---

## 12. プラットフォームでの使用方法

### 12.1 Paper / Velocity（ネイティブサポート）

Paper と Velocity は Adventure API を**ネイティブ実装**しているため、追加の依存なしで `Component` が直接使える。

```java
// Paperプラグインでの使用例
Player player = /* ... */;

// チャットメッセージ送信
player.sendMessage(Component.text("Hello!", NamedTextColor.GREEN));

// MiniMessageの使用
player.sendMessage(MiniMessage.miniMessage().deserialize(
    "<gradient:green:aqua>Welcome to the server!</gradient>"
));

// アクションバー
player.sendActionBar(Component.text("Action bar text", NamedTextColor.YELLOW));

// キックメッセージ
player.kick(Component.text("You have been kicked!", NamedTextColor.RED));
```

### 12.2 非推奨メソッドとの対応

Paper APIでは多くのレガシー `String` パラメータのメソッドが `@Deprecated` になっており、`Component` を受け取る代替メソッドが用意されている。

```java
// ❌ 非推奨
player.sendMessage("§cHello");
itemMeta.setDisplayName("§6Gold Sword");

// ✓ 推奨
player.sendMessage(Component.text("Hello", NamedTextColor.RED));
itemMeta.displayName(Component.text("Gold Sword", NamedTextColor.GOLD));
```

---

## 13. よくあるパターンとベストプラクティス

### 13.1 プレフィックス付きメッセージ

```java
final Component PREFIX = Component.text()
    .append(Component.text("[", NamedTextColor.DARK_GRAY))
    .append(Component.text("MyPlugin", NamedTextColor.GOLD))
    .append(Component.text("] ", NamedTextColor.DARK_GRAY))
    .build();

// 使用
player.sendMessage(PREFIX.append(Component.text("Message here", NamedTextColor.WHITE)));
```

### 13.2 クリック可能なURLリンク

```java
Component link = Component.text("Click to visit our website!")
    .color(NamedTextColor.AQUA)
    .decorate(TextDecoration.UNDERLINED)
    .clickEvent(ClickEvent.openUrl("https://example.com"))
    .hoverEvent(HoverEvent.showText(
        Component.text("Click to open in browser", NamedTextColor.GRAY)
    ));
```

### 13.3 変数を含むメッセージ（MiniMessage Placeholder）

```java
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

Component msg = MiniMessage.miniMessage().deserialize(
    "<green><player> has joined the game!",
    Placeholder.unparsed("player", playerName)  // MiniMessageタグとして解釈しない
);

// パース付き（MiniMessageタグを許可する場合）
Component msg2 = MiniMessage.miniMessage().deserialize(
    "<green>Welcome, <player_display>!",
    Placeholder.component("player_display", player.displayName())
);
```

### 13.4 joinSeparatorを使ったリスト表示

```java
Component list = Component.join(
    JoinConfiguration.separator(Component.text(", ", NamedTextColor.GRAY)),
    playerNames.stream()
        .map(name -> Component.text(name, NamedTextColor.WHITE))
        .toList()
);
```

---

## 14. 重要な注意事項

1. **Component は不変 (immutable)**: `component.color(RED)` は元のコンポーネントを変更せず、新しいインスタンスを返す
2. **MiniMessage を最優先で使う**: ユーザが編集する設定やコマンドには MiniMessage が最適
3. **LegacyComponentSerializer は避ける**: 旧プラグインの移行時のみ使用し、新規開発では使わない
4. **Paper/Velocity では追加依存不要**: Adventure はネイティブ同梱されている
5. **`PaperComponents` クラスは非推奨**: 直接 `PlainTextComponentSerializer.plainText()` や `GsonComponentSerializer.gson()` 等を使用すること
6. **スタイルはツリーで継承される**: 親コンポーネントのスタイルは子に自動的に継承される。無効化するには明示的に `decoration(BOLD, false)` のように指定する

---

## 15. 参考リンク

- **Adventure ドキュメント**: <https://docs.papermc.io/adventure/>
- **Adventure Javadoc**: <https://jd.advntr.dev>
- **MiniMessage Webビューア**: <https://webui.advntr.dev/>
- **Paper Component API 入門**: <https://docs.papermc.io/paper/dev/component-api/introduction/>
- **Minecraft Wiki テキストコンポーネント形式**: <https://minecraft.wiki/w/Text_component_format>
- **JSON Text Generator**: <https://minecraft.tools/en/json_text.php>
