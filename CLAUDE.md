# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# プラグイン JAR をビルド (Shadow プラグインで依存をバンドル)
./gradlew shadowJar

# ライブラリ版をビルド (API 依存として配布用)
./gradlew libJar javadocJar sourcesJar

# ローカルリポジトリへパブリッシュ
./gradlew publishLibPublicationToLocalRepoRepository

# ビルド成果物を削除
./gradlew clean

# テスト (Bukkit サーバ不要、SQLite で実行)
./gradlew test
```

デフォルトビルドは `build/libs/Jecon-<version>.jar` を生成し、
依存ライブラリ (JBukkitLib, HikariCP, SLF4J) を `jp.jyn.jecon.lib.*` に
リロケートしてバンドルする。

### テスト

`src/test/java` に JUnit 5 のテストがある。Bukkit サーバを起動せずに動く
(`YamlConfiguration` は純 Java、DB は SQLite / Testcontainers の MySQL)。
`jp.jyn.jecon.testing.TestFixture` がバンドル済み `config.yml` を読んで `Database` を組み立てる。

DB 依存のテストは `BackendTestBase` を継承し、`Sqlite*Test` / `Mysql*Test` の 2 つの
サブクラスで**両 driver で回す**。MySQL 側は Docker が無いと skip されるので、
`./gradlew test -Djecon.test.mysql=true` で明示的に要求できる (CI はこれを使う)。

**ロック周りを変更したら必ず MySQL でも回すこと。** MySQL の既定分離レベルは
REPEATABLE READ で、行ロックを取った後の通常の SELECT もトランザクション開始時点の
スナップショットを返す。一方 SQLite は `BEGIN IMMEDIATE` で全 writer を直列化するため、
「ロックしてから読んで書き戻す」コードは SQLite では通って MySQL で lost update する。
実際にこの差で 1 件バグを出している (ADR-0014 の 2-b)。

並行性のテスト (総額保存、権限ビットの lost update、孤児 balance 行、`setBalance` の stale read) が
本体の設計を支えているので、書き込み経路を触るときは必ず実行する。
`docs/spec/adr/0014-thread-safe-write-path.md` に各テストが検出する不具合を一覧してある。

**新しく競合系のテストを書いたら、修正前のコードに対して実際に落ちることを確認すること。**
並行性のテストは黙って無力化されやすい (例: 最終残高だけを見る `setBalance` のテストは
バグのあるコードでも通ってしまう)。

サーバ上での最終確認は引き続き Spigot/Paper に JAR をデプロイして行う。

## Architecture Overview

Jecon is a Bukkit/Spigot economy plugin for Minecraft that integrates with the [Vault](https://github.com/MilkBowl/VaultAPI) economy API.

### Entry Point & Lifecycle

`Jecon.java` is the main `JavaPlugin` class. It uses a LIFO destructor stack (`Deque<Runnable>`) to register cleanup handlers during `onEnable()` and execute them in reverse order during `onDisable()`. This ensures proper teardown ordering (e.g., save data before closing DB).

### Repository Layer

The central interface is `BalanceRepository` — this is the public API exposed to other plugins via `Jecon.getRepository()`.

The only implementation is **`SyncRepository`**, which reads from the database directly and
delegates every write to `TransferService` so it goes through the modifier pipeline and the
audit log (ADR-0010 / ADR-0012). `LazyRepository` was removed in ADR-0012.

`AbstractRepository` handles all the type conversion (double/BigDecimal ↔ internal long
representation, cents = value × 100) and formatting logic. Subclasses only implement
`getRaw()`, `set()`, `deposit()`, and `createAccount()`.

### Database Layer

`Database` is an abstract class with two concrete drivers: `MySQL` and `SQLite`. Connection pooling is handled by HikariCP. The schema uses two tables:
- `account(id INT, uuid BYTES)` — maps UUIDs to integer IDs
- `balance(id INT, balance LONG)` — stores balance in cents (×100)

Every method has a `Connection`-taking primitive that joins the caller's transaction, plus a
convenience overload that opens its own connection. Multi-step logic must use the primitives
inside `inTransaction` / `inTransactionWithRetry` — see ADR-0014.

Balance-row presence is what `hasAccount` means, so `getOrCreatePlayerId` deliberately does
not create one and `setBalanceInTx` never inserts.

### Command System

Commands use `SubExecutor` from JBukkitLib. Each subcommand (`show`, `pay`, `set`, `give`, `take`, `create`, `remove`, `top`, `convert`, `reload`, `version`) is a separate class in `jp.jyn.jecon.command`. The main command is registered as both `/jecon` and `/money`.

### Config System

`ConfigLoader` manages two config files (`config.yml` and `message.yml`). `MainConfig` and `MessageConfig` are immutable value objects populated at load time. Migration classes (`config/migration/`) handle upgrading config files from older versions.

### Vault Integration

`VaultEconomy` implements the Vault `Economy` interface. If Vault loads after Jecon, a `VaultRegister` listener waits for `PluginEnableEvent` before hooking in.

### Key Design Decisions

- Balances are stored internally as `long` (integer cents) to avoid floating-point precision issues. The public API accepts both `double` and `BigDecimal`.
- The `account` table stores UUIDs as raw bytes for compactness and MySQL compatibility.
- **Every public API is callable from any thread** (ADR-0014). Vault's `Economy` is a synchronous
  interface, so third-party plugins call it from their own threads and Jecon cannot prevent that.
  Correctness therefore comes from database transactions, not from a thread confinement rule —
  which also keeps it correct across the multiple Paper servers a MySQL setup implies.
  Concretely: lock `account` rows (never rows that may be absent, to avoid InnoDB gap-lock
  deadlocks), take multiple locks in ascending `account.id` order, keep non-DB side effects
  (events, logging) out of transactions because they are retried, and use SQLite's
  `BEGIN IMMEDIATE` so read-to-write lock upgrades cannot fail with `SQLITE_BUSY`.
- Bukkit events are queued and dispatched on the main thread in commit order, one tick behind
  at the earliest, so they fire after `transfer()` has already returned to its caller.