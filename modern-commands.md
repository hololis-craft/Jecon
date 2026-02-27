# PaperMC Command API リファレンス

> **対象バージョン**: Paper 1.21.1 以降（Requirements の `Commands.restricted()` は 1.21.6 以降）
> **情報源**: <https://docs.papermc.io/paper/dev/command-api/>
> **目的**: AI がコード生成・レビュー時に参照するための技術リファレンス

---

## 1. 概要

Paper の Command API は Minecraft の **Brigadier** コマンドシステムの上に構築されている。旧来の Bukkit コマンドシステムに比べ、以下の利点がある。

- 引数のパース・エラーチェックを開発者が手動で行う必要がない
- クライアント側でのエラー表示による優れた UX
- `/reload` イベントとの統合（LifecycleEventManager 経由）
- サブコマンドの簡潔な定義

### 中核クラス一覧

| クラス / インタフェース | パッケージ | 役割 |
|---|---|---|
| `Commands` | `io.papermc.paper.command.brigadier` | `literal()` / `argument()` のファクトリ。コマンドツリー構築の起点 |
| `CommandSourceStack` | `io.papermc.paper.command.brigadier` | コマンド実行コンテキスト。`getSender()`, `getExecutor()`, `getLocation()` を持つ |
| `LiteralArgumentBuilder<CommandSourceStack>` | `com.mojang.brigadier.builder` | リテラル（サブコマンド）ノードのビルダー |
| `RequiredArgumentBuilder<CommandSourceStack, T>` | `com.mojang.brigadier.builder` | 引数ノードのビルダー |
| `LiteralCommandNode<CommandSourceStack>` | `com.mojang.brigadier.tree` | ビルド済みコマンドツリーのルートノード |
| `ArgumentType<T>` | `com.mojang.brigadier.arguments` | 引数型インタフェース（`IntegerArgumentType`, `StringArgumentType` 等） |
| `ArgumentTypes` | `io.papermc.paper.command.brigadier.argument` | Paper 固有引数型のファクトリ |
| `CustomArgumentType<T, N>` | `io.papermc.paper.command.brigadier.argument` | カスタム引数型インタフェース |
| `BasicCommand` | `io.papermc.paper.command.brigadier` | Bukkit 風の簡易コマンドインタフェース |
| `LifecycleEventManager` | `io.papermc.paper.plugin.lifecycle.event` | コマンド登録のライフサイクル管理 |

---

## 2. コマンドツリー（Command Tree）

Brigadier コマンドはツリー構造で表現される。

- **ルートノード**: `Commands.literal("コマンド名")` で生成。コマンド名そのもの
- **ブランチ（子ノード）**: `.then()` で追加。リテラルまたは引数
- **実行ロジック**: `.executes()` で定義。これがないブランチは実行不可

### ツリーの可視化例

```
/myplugin
├── reload        ← リテラル（サブコマンド）
├── tphere        ← リテラル（サブコマンド）
│   └── <player>  ← 引数
└── killall       ← リテラル（サブコマンド）
    ├── entities  ← リテラル
    └── players   ← リテラル
```

### コード例: 基本的なツリー構築

```java
Commands.literal("myplugin")
    .then(Commands.literal("reload")
        .executes(ctx -> { /* reload 処理 */ return Command.SINGLE_SUCCESS; })
    )
    .then(Commands.literal("tphere")
        .then(Commands.argument("target", ArgumentTypes.player())
            .executes(ctx -> { /* テレポート処理 */ return Command.SINGLE_SUCCESS; })
        )
    )
    .then(Commands.literal("killall")
        .then(Commands.literal("entities")
            .executes(ctx -> { /* エンティティ全殺し */ return Command.SINGLE_SUCCESS; })
        )
        .then(Commands.literal("players")
            .executes(ctx -> { /* プレイヤー全殺し */ return Command.SINGLE_SUCCESS; })
        )
    );
```

### 複雑なツリーの構築パターン

末端から構築していく「furthest from root first」パターンが推奨される。

```java
// 1. 末端ノードを定義
LiteralArgumentBuilder<CommandSourceStack> entities = Commands.literal("entities");
LiteralArgumentBuilder<CommandSourceStack> players = Commands.literal("players");

// 2. 中間ノードに末端を追加
LiteralArgumentBuilder<CommandSourceStack> killall = Commands.literal("killall")
    .then(entities)
    .then(players);

// 3. ルートノードに追加
LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("myplugin")
    .then(killall);
```

> **注意**: `.then()` のネストを間違えるとコマンド構造が変わってしまう。ブラケットの位置に注意すること。

---

## 3. 引数とリテラル（Arguments and Literals）

### リテラル（LiteralArgumentBuilder）

`Commands.literal(String)` で作成する固定文字列ノード。サブコマンドとして機能する。

```java
Commands.literal("reload")   // /myplugin reload
Commands.literal("give")     // /myplugin give
```

- コード内からリテラルの値を取得することは通常しない
- ツリー構造により、どのリテラルブランチにいるかは常に判定可能

### 引数（RequiredArgumentBuilder）

`Commands.argument(String, ArgumentType<T>)` で作成する可変入力ノード。

```java
Commands.argument("speed", FloatArgumentType.floatArg(0, 1.0f))
Commands.argument("target", ArgumentTypes.player())
Commands.argument("message", StringArgumentType.greedyString())
```

- `ArgumentType<T>` の `T` が引数の返却型を決定する
- クライアント側でリアルタイムにバリデーションが実行される

### Brigadier 組み込み引数型

| 型 | 作成方法 | 返却型 | 説明 |
|---|---|---|---|
| Boolean | `BoolArgumentType.bool()` | `boolean` | true/false |
| Integer | `IntegerArgumentType.integer()` | `int` | 整数（範囲指定可） |
| Integer (範囲) | `IntegerArgumentType.integer(min, max)` | `int` | 範囲付き整数 |
| Long | `LongArgumentType.longArg()` | `long` | 長整数 |
| Float | `FloatArgumentType.floatArg()` | `float` | 浮動小数点 |
| Float (範囲) | `FloatArgumentType.floatArg(min, max)` | `float` | 範囲付き浮動小数点 |
| Double | `DoubleArgumentType.doubleArg()` | `double` | 倍精度浮動小数点 |
| Word | `StringArgumentType.word()` | `String` | 単一単語（英数字 + `+-_.`） |
| String | `StringArgumentType.string()` | `String` | 引用符で囲むと任意 Unicode 可 |
| Greedy String | `StringArgumentType.greedyString()` | `String` | 残り全入力を消費 |

### 引数値の取得方法

```java
// 方法 1: CommandContext#getArgument
float speed = ctx.getArgument("speed", float.class);
String name = ctx.getArgument("name", String.class);

// 方法 2: ArgumentType 固有のヘルパー（プリミティブ型向け）
float speed = FloatArgumentType.getFloat(ctx, "speed");
int count = IntegerArgumentType.getInteger(ctx, "count");
```

どちらも内部的に同じロジック。プリミティブ型にはヘルパーメソッドが推奨される。

### Paper 固有引数型（ArgumentTypes クラス経由）

`ArgumentTypes` クラスから静的にアクセスできる。主要なものは以下。

| 引数 | 作成方法 | 返却型 | 説明 |
|---|---|---|---|
| プレイヤー | `ArgumentTypes.player()` | `PlayerSelectorArgumentResolver` | 単一プレイヤーセレクター |
| プレイヤー(複数) | `ArgumentTypes.players()` | `PlayerSelectorArgumentResolver` | 複数プレイヤーセレクター |
| エンティティ | `ArgumentTypes.entity()` | `EntitySelectorArgumentResolver` | 単一エンティティセレクター |
| エンティティ(複数) | `ArgumentTypes.entities()` | `EntitySelectorArgumentResolver` | 複数エンティティセレクター |
| ブロック座標 | `ArgumentTypes.blockPosition()` | `BlockPositionResolver` | ブロック座標 |
| 細密座標 | `ArgumentTypes.finePosition(centerIntegers)` | `FinePositionResolver` | 小数座標 |
| ワールド | `ArgumentTypes.world()` | `World` | ワールド |
| ItemStack | `ArgumentTypes.itemStack()` | `ItemStack` | アイテムスタック |
| BlockState | `ArgumentTypes.blockState()` | `BlockState` | ブロック状態 |
| NamespacedKey | `ArgumentTypes.namespacedKey()` | `NamespacedKey` | 名前空間付きキー |
| UUID | `ArgumentTypes.uuid()` | `UUID` | UUID |
| Time | `ArgumentTypes.time()` | `int` | 時間（tick 単位） |
| Component | `ArgumentTypes.component()` | `Component` | Adventure コンポーネント |

### セレクター引数の解決（Resolve）

`PlayerSelectorArgumentResolver` などのセレクター型は、取得後に **resolve** が必要。

```java
.executes(ctx -> {
    // セレクターリゾルバを取得
    PlayerSelectorArgumentResolver selector =
        ctx.getArgument("target", PlayerSelectorArgumentResolver.class);

    // CommandSourceStack を使って実際の Player に解決
    Player target = selector.resolve(ctx.getSource()).getFirst();

    target.sendPlainMessage("You have been selected!");
    return Command.SINGLE_SUCCESS;
})
```

---

## 4. エグゼキュータ（Executors）

### executes メソッド

```java
public T executes(Command<S> command);
```

`Command<S>` は `@FunctionalInterface` であり、ラムダまたはメソッド参照を渡せる。

```java
@FunctionalInterface
public interface Command<S> {
    int SINGLE_SUCCESS = 1;
    int run(CommandContext<S> ctx) throws CommandSyntaxException;
}
```

### CommandContext から取得できる情報

```java
.executes(ctx -> {
    // コマンドソーススタック
    CommandSourceStack source = ctx.getSource();

    // コマンド送信者（実際にコマンドを打った主体）
    CommandSender sender = source.getSender();

    // コマンド実行者（/execute as で変更される可能性がある）
    // null の場合がある
    @Nullable Entity executor = source.getExecutor();

    // コマンド実行位置
    Location location = source.getLocation();

    // 引数の取得
    float speed = FloatArgumentType.getFloat(ctx, "speed");

    return Command.SINGLE_SUCCESS;
})
```

### sender vs executor の違い

| 状況 | `getSender()` | `getExecutor()` |
|---|---|---|
| プレイヤーが直接実行 | そのプレイヤー | そのプレイヤー |
| コンソールが実行 | ConsoleCommandSender | `null` |
| `/execute as @e run ...` | 元のコマンド送信者 | 対象エンティティ |

**ベストプラクティス**: コマンドの「対象」には `getExecutor()` を使い、フィードバック送信には `getSender()` を使う。

### 完全な例: /flyspeed コマンド

```java
public class FlightSpeedCommand {

    public static LiteralArgumentBuilder<CommandSourceStack> createCommand() {
        return Commands.literal("flyspeed")
            .then(Commands.argument("speed", FloatArgumentType.floatArg(0, 1.0f))
                .executes(FlightSpeedCommand::runFlySpeedLogic)
            );
    }

    private static int runFlySpeedLogic(CommandContext<CommandSourceStack> ctx) {
        float speed = FloatArgumentType.getFloat(ctx, "speed");
        CommandSender sender = ctx.getSource().getSender();
        Entity executor = ctx.getSource().getExecutor();

        if (!(executor instanceof Player player)) {
            sender.sendPlainMessage("Only players can fly!");
            return Command.SINGLE_SUCCESS;
        }

        player.setFlySpeed(speed);

        if (sender == executor) {
            player.sendPlainMessage("Successfully set your flight speed to " + speed);
        } else {
            sender.sendRichMessage(
                "Successfully set <playername>'s flight speed to " + speed,
                Placeholder.component("playername", player.name())
            );
            player.sendPlainMessage("Your flight speed has been set to " + speed);
        }

        return Command.SINGLE_SUCCESS;
    }
}
```

### ロジックの分離

ラムダが長くなる場合、メソッド参照を使って分離する。

```java
// ラムダ内にロジックを書く代わりに
.executes(FlightSpeedCommand::runFlySpeedLogic)

// 別メソッドとして定義
private static int runFlySpeedLogic(CommandContext<CommandSourceStack> ctx) {
    // 処理...
    return Command.SINGLE_SUCCESS;
}
```

---

## 5. コマンドの登録（Registration）

### LifecycleEventManager による登録

Paper では `LifecycleEventManager` を通じてコマンドを登録する。これにより `/reload` 時に自動で再登録される。

#### 方法 A: PluginBootstrap 内（推奨、paper-plugin.yml 必須）

```java
public class CustomPluginBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            // ここでコマンドを登録
            commands.registrar().register(
                FlightSpeedCommand.createCommand().build()
            );
        });
    }
}
```

#### 方法 B: JavaPlugin メインクラス内

```java
public final class MyPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            // ここでコマンドを登録
            commands.registrar().register(
                FlightSpeedCommand.createCommand().build()
            );
        });
    }
}
```

### LifecycleEventManager の内部構造

```
LifecycleEventManager
  └── registerEventHandler(LifecycleEvents.COMMANDS, handler)
        └── handler: LifecycleEventHandler<ReloadableRegistrarEvent<Commands>>
              └── event.registrar() → Commands インスタンス
                    ├── register(LiteralCommandNode<CommandSourceStack>)
                    ├── register(LiteralCommandNode, String description)
                    ├── register(LiteralCommandNode, String description, List<String> aliases)
                    ├── register(String name, BasicCommand)
                    └── register(String name, BasicCommand, String description, List<String> aliases)
```

### LiteralCommandNode の登録

```java
// ビルドしてから登録
LiteralCommandNode<CommandSourceStack> node = Commands.literal("testcmd")
    .then(Commands.literal("sub1"))
    .then(Commands.literal("sub2"))
    .build();

commands.registrar().register(node);

// エイリアスと説明付き
commands.registrar().register(node, "テストコマンドの説明", List.of("tcmd", "tc"));
```

### BasicCommand の登録

```java
BasicCommand broadcastCmd = new BroadcastCommand();
commands.registrar().register("broadcast", broadcastCmd);

// エイリアスと説明付き
commands.registrar().register(
    "broadcast", broadcastCmd,
    "サーバー全体にメッセージを送信", List.of("bc", "announce")
);
```

---

## 6. 要件（Requirements）

### requires メソッド

`ArgumentBuilder` の `requires(Predicate<CommandSourceStack>)` でコマンドの使用条件を定義する。

```java
Commands.literal("testcmd")
    .requires(source -> source.getSender().hasPermission("myplugin.test"))
    .executes(ctx -> { /* ... */ return Command.SINGLE_SUCCESS; });
```

### パーミッションチェック

```java
// 特定パーミッション
.requires(source -> source.getSender().hasPermission("myplugin.admin"))

// OP 権限
.requires(source -> source.getSender().isOp())
```

### 高度な条件

```java
// インベントリにダイヤモンドソードがないプレイヤーのみ
.requires(source ->
    source.getExecutor() instanceof Player player
    && !player.getInventory().contains(Material.DIAMOND_SWORD)
)
```

### クライアント同期の問題と解決策

requires の結果が動的に変化する場合、クライアントの表示と実際の状態がずれる。`Player#updateCommands()` で再同期する。

```java
// イベントリスナー等で自動更新
@EventHandler
public void onInventoryChange(InventoryClickEvent event) {
    if (event.getWhoClicked() instanceof Player player) {
        player.updateCommands(); // コマンド状態を再送信
    }
}
```

> **注意**: `updateCommands()` は帯域幅を消費するため、頻繁な呼び出しは避ける。スレッドセーフ。

### Restricted コマンド（1.21.6 以降）

`Commands.restricted()` でコマンド実行前に確認ダイアログを表示できる（バニラの OP コマンドと同様の挙動）。

```java
Commands.literal("dangerouscommand")
    .requires(Commands.restricted(
        source -> source.getSender().hasPermission("myplugin.dangerous")
    ))
    .executes(ctx -> { /* ... */ return Command.SINGLE_SUCCESS; });
```

---

## 7. サジェスチョン（Suggestions）

### suggests メソッド

引数に `suggests(SuggestionProvider<CommandSourceStack>)` を設定してカスタムサジェスチョンを定義する。

```java
@FunctionalInterface
public interface SuggestionProvider<S> {
    CompletableFuture<Suggestions> getSuggestions(
        CommandContext<S> context,
        SuggestionsBuilder builder
    ) throws CommandSyntaxException;
}
```

### 基本的なサジェスチョン定義

#### 同期方式（Paper API の使用可）

```java
Commands.argument("name", StringArgumentType.word())
    .suggests((ctx, builder) -> {
        builder.suggest("first");
        builder.suggest("second");
        return builder.buildFuture();
    })
```

#### 非同期方式（Paper API の使用に注意）

```java
Commands.argument("name", StringArgumentType.word())
    .suggests((ctx, builder) -> CompletableFuture.supplyAsync(() -> {
        builder.suggest("first");
        builder.suggest("second");
        return builder.build();
    }))
```

### SuggestionsBuilder の主要メソッド

| メソッド | 説明 |
|---|---|
| `suggest(String)` | 文字列サジェスチョンを追加 |
| `suggest(int)` | 整数サジェスチョンを追加 |
| `suggest(String, Message)` | ツールチップ付きサジェスチョンを追加 |
| `getInput()` | 入力全体 |
| `getRemaining()` | 現在の引数の入力途中テキスト |
| `getRemainingLowerCase()` | 同上の小文字版 |
| `getStart()` | 現在の引数の開始位置 |
| `buildFuture()` | `CompletableFuture<Suggestions>` を返す（同期用） |
| `build()` | `Suggestions` を返す（非同期用） |

### Message の作成方法

```java
// 方法 1: LiteralMessage（プレーンテキスト）
builder.suggest("value", new LiteralMessage("説明テキスト"));

// 方法 2: MessageComponentSerializer（Adventure Component）
Message tooltip = MessageComponentSerializer.message().serialize(
    Component.text("リッチな説明", NamedTextColor.GOLD)
);
builder.suggest("value", tooltip);
```

### 実用例: アイテム個数サジェスチョン

```java
public class GiveItemCommand {

    public static LiteralCommandNode<CommandSourceStack> create() {
        return Commands.literal("giveitem")
            .then(Commands.argument("item", ArgumentTypes.itemStack())
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                    .suggests(GiveItemCommand::getAmountSuggestions)
                    .executes(GiveItemCommand::execute)
                )
            )
            .build();
    }

    private static CompletableFuture<Suggestions> getAmountSuggestions(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        builder.suggest(1);
        builder.suggest(16);
        builder.suggest(32);
        builder.suggest(64);
        return builder.buildFuture();
    }

    // ...
}
```

### ユーザー入力によるフィルタリング

```java
.suggests((ctx, builder) -> {
    String remaining = builder.getRemainingLowerCase();
    for (Player player : Bukkit.getOnlinePlayers()) {
        if (player.getName().toLowerCase().startsWith(remaining)) {
            builder.suggest(player.getName());
        }
    }
    return builder.buildFuture();
})
```

---

## 8. カスタム引数（Custom Arguments）

### CustomArgumentType インタフェース

```java
public interface CustomArgumentType<T, N> extends ArgumentType<T> {
    T parse(StringReader reader) throws CommandSyntaxException;
    ArgumentType<N> getNativeType();

    // オプション: カスタムサジェスチョン
    default <S> CompletableFuture<Suggestions> listSuggestions(
        CommandContext<S> context, SuggestionsBuilder builder) {
        return ArgumentType.super.listSuggestions(context, builder);
    }
}
```

#### ジェネリクス

- `T`: `CommandContext#getArgument()` で返される型
- `N`: ベースとなるネイティブ引数型
- `S`: コマンドソース型（通常 `CommandSourceStack`）

### 使用例: OP プレイヤー引数

```java
public final class OppedPlayerArgument
        implements CustomArgumentType<Player, PlayerSelectorArgumentResolver> {

    private static final SimpleCommandExceptionType ERROR_BAD_SOURCE =
        new SimpleCommandExceptionType(
            MessageComponentSerializer.message().serialize(
                Component.text("The source needs to be a CommandSourceStack!")
            )
        );

    private static final DynamicCommandExceptionType ERROR_NOT_OPERATOR =
        new DynamicCommandExceptionType(name ->
            MessageComponentSerializer.message().serialize(
                Component.text(name + " is not a server operator!")
            )
        );

    @Override
    public Player parse(StringReader reader) throws CommandSyntaxException {
        // parse ロジック
        throw new UnsupportedOperationException("Use parse(reader, source) instead");
    }

    @Override
    public <S> Player parse(StringReader reader, S source) throws CommandSyntaxException {
        if (!(source instanceof CommandSourceStack stack)) {
            throw ERROR_BAD_SOURCE.create();
        }
        // ネイティブ型でパースして解決
        PlayerSelectorArgumentResolver resolver =
            ArgumentTypes.player().parse(reader, source);
        Player player = resolver.resolve(stack).getFirst();
        if (!player.isOp()) {
            throw ERROR_NOT_OPERATOR.create(player.getName());
        }
        return player;
    }

    @Override
    public ArgumentType<PlayerSelectorArgumentResolver> getNativeType() {
        return ArgumentTypes.player();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(
            CommandContext<S> ctx, SuggestionsBuilder builder) {
        // OP プレイヤーのみサジェスト
        String remaining = builder.getRemainingLowerCase();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOp()
                    && player.getName().toLowerCase().startsWith(remaining)) {
                builder.suggest(player.getName());
            }
        }
        return builder.buildFuture();
    }
}
```

#### 使用時

```java
Commands.argument("player", new OppedPlayerArgument())
    .executes(ctx -> {
        Player player = ctx.getArgument("player", Player.class);
        // player は確実に OP
        return Command.SINGLE_SUCCESS;
    })
```

### CustomArgumentType.Converted<T, N>

ネイティブ型からの変換が主目的の場合に便利なサブインタフェース。

```java
public interface Converted<T, N> extends CustomArgumentType<T, N> {
    T convert(N nativeType) throws CommandSyntaxException;
    default <S> T convert(N nativeType, S source) throws CommandSyntaxException {
        return convert(nativeType);
    }
}
```

`StringReader` を直接操作する代わりに、パース済みのネイティブ型を受け取れる。

### エラーハンドリング

カスタム引数のエラーには Brigadier の例外型を使用する。

```java
// 静的なエラーメッセージ
private static final SimpleCommandExceptionType ERROR =
    new SimpleCommandExceptionType(
        MessageComponentSerializer.message().serialize(Component.text("エラー内容"))
    );

// 動的なエラーメッセージ（引数付き）
private static final DynamicCommandExceptionType DYNAMIC_ERROR =
    new DynamicCommandExceptionType(arg ->
        MessageComponentSerializer.message().serialize(
            Component.text(arg + " は無効な値です")
        )
    );

// 使用時
throw ERROR.create();
throw DYNAMIC_ERROR.create(invalidValue);
```

> **注意**: サジェスチョンフェーズでクライアントに赤色の入力エラー表示を出すことは現在サポートされていない。

---

## 9. BasicCommand（簡易コマンド）

Bukkit スタイルの簡易コマンド定義。ツリー構築が不要で、引数は `String[]` で受け取る。

### インタフェース定義

```java
public interface BasicCommand {
    // 必須
    void execute(CommandSourceStack source, String[] args);

    // オプション
    Collection<String> suggest(CommandSourceStack source, String[] args);
    boolean canUse(CommandSender sender);
    @Nullable String permission();
}
```

### 使用例: /broadcast コマンド

```java
public class BroadcastCommand implements BasicCommand {

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        Component name = source.getExecutor() != null
            ? source.getExecutor().name()
            : Component.text(source.getSender().getName());

        if (args.length == 0) {
            source.getSender().sendPlainMessage("Usage: /broadcast <message>");
            return;
        }

        String message = String.join(" ", args);
        Bukkit.broadcast(
            Component.text("[Broadcast] ").append(name).append(Component.text(": " + message))
        );
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length == 0) {
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .toList();
        }
        // 入力中のテキストでフィルタリング
        String current = args[args.length - 1].toLowerCase();
        return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .filter(name -> name.toLowerCase().startsWith(current))
            .toList();
    }

    @Override
    public @Nullable String permission() {
        return "example.broadcast.use";
    }
}
```

### 登録

```java
this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
    commands.registrar().register(
        "broadcast",
        new BroadcastCommand(),
        "サーバー全体にメッセージを送信",
        List.of("bc")
    );
});
```

### BasicCommand と Brigadier コマンドの使い分け

| 観点 | BasicCommand | Brigadier |
|---|---|---|
| 学習コスト | 低い | やや高い |
| 引数パース | 手動（`String[]`） | 自動（型安全） |
| クライアント補完 | 基本的（suggest のみ） | 高度（型に基づく） |
| サブコマンド | 手動分岐 | ツリー構造で定義 |
| 適用場面 | `/broadcast`, `/msg` 等の単純コマンド | 複雑なサブコマンド体系を持つコマンド |

---

## 10. 完全なプラグイン例

### プロジェクト構成

```
src/main/java/com/example/myplugin/
├── MyPlugin.java                    ← メインクラス
├── MyPluginBootstrap.java           ← ブートストラップ（推奨）
└── commands/
    ├── FlightSpeedCommand.java      ← Brigadier コマンド
    └── BroadcastCommand.java        ← BasicCommand
```

### MyPluginBootstrap.java

```java
package com.example.myplugin;

import com.example.myplugin.commands.FlightSpeedCommand;
import com.example.myplugin.commands.BroadcastCommand;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

public class MyPluginBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            // Brigadier コマンド
            commands.registrar().register(
                FlightSpeedCommand.createCommand().build(),
                "飛行速度を設定する",
                java.util.List.of("fs")
            );

            // BasicCommand
            commands.registrar().register(
                "broadcast",
                new BroadcastCommand(),
                "サーバー全体にメッセージを送信",
                java.util.List.of("bc")
            );
        });
    }
}
```

### paper-plugin.yml

```yaml
name: MyPlugin
version: '1.0.0'
main: com.example.myplugin.MyPlugin
bootstrapper: com.example.myplugin.MyPluginBootstrap
api-version: '1.21'
```

---

## 11. よくあるパターンとベストプラクティス

### コマンド実行者の型チェック

```java
.executes(ctx -> {
    Entity executor = ctx.getSource().getExecutor();
    if (!(executor instanceof Player player)) {
        ctx.getSource().getSender().sendPlainMessage("このコマンドはプレイヤーのみ使用可能です");
        return Command.SINGLE_SUCCESS;
    }
    // player を使った処理
    return Command.SINGLE_SUCCESS;
})
```

### サブコマンドのパーミッション分離

```java
Commands.literal("admin")
    .then(Commands.literal("reload")
        .requires(s -> s.getSender().hasPermission("myplugin.admin.reload"))
        .executes(ctx -> { /* reload */ return Command.SINGLE_SUCCESS; })
    )
    .then(Commands.literal("reset")
        .requires(s -> s.getSender().hasPermission("myplugin.admin.reset"))
        .executes(ctx -> { /* reset */ return Command.SINGLE_SUCCESS; })
    );
```

### リッチメッセージの送信

```java
// MiniMessage 形式
sender.sendRichMessage("<gold>Success! <white>Speed set to <green><speed></green>",
    Placeholder.component("speed", Component.text(speed)));

// プレーンテキスト
sender.sendPlainMessage("Simple message without formatting");
```

### 返却値の慣例

- `Command.SINGLE_SUCCESS` (= `1`): コマンド成功
- 任意の正の整数: 成功（値はコマンドブロック等で利用可能）
- `0` または負の値: 失敗

---

## 12. トラブルシューティング

| 問題 | 原因 | 解決策 |
|---|---|---|
| コマンドが表示されない | `requires` が `false` を返している | パーミッション設定を確認。`updateCommands()` を呼ぶ |
| 引数が取得できない | 引数名の不一致 | `Commands.argument("name", ...)` と `ctx.getArgument("name", ...)` の名前を一致させる |
| `/reload` 後にコマンドが消える | LifecycleEventManager を使っていない | `LifecycleEvents.COMMANDS` で登録する |
| `ClassCastException` | 引数型の不一致 | `getArgument` の第2引数に正しいクラスを指定する |
| セレクター引数が解決できない | `resolve()` を呼んでいない | `PlayerSelectorArgumentResolver` は `resolve(source)` が必要 |
| クライアントの表示がサーバーとずれる | 動的な `requires` | `Player#updateCommands()` で再同期 |

---

## 13. 関連リンク

- **公式ドキュメント**: <https://docs.papermc.io/paper/dev/command-api/basics/introduction/>
- **Javadoc**: <https://jd.papermc.io/paper/>
- **Brigadier GitHub**: <https://github.com/Mojang/brigadier>
- **Discord サポート**: <https://discord.gg/PaperMC> (#paper-dev)
