# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build the plugin JAR (default, uses shade plugin to bundle dependencies)
mvn package

# Build the library version (for publishing as API dependency)
mvn -P lib clean javadoc:jar source:jar deploy

# Clean build artifacts
mvn clean
```

The default Maven profile (`plugin`) produces a shaded JAR at `target/Jecon-<version>.jar` with bundled dependencies (JBukkitLib, HikariCP, SLF4J) relocated to `jp.jyn.jecon.lib.*`.

There are no unit tests in this codebase — testing is done by deploying the JAR to a Spigot server.

## Architecture Overview

Jecon is a Bukkit/Spigot economy plugin for Minecraft that integrates with the [Vault](https://github.com/MilkBowl/VaultAPI) economy API.

### Entry Point & Lifecycle

`Jecon.java` is the main `JavaPlugin` class. It uses a LIFO destructor stack (`Deque<Runnable>`) to register cleanup handlers during `onEnable()` and execute them in reverse order during `onDisable()`. This ensures proper teardown ordering (e.g., save data before closing DB).

### Repository Layer

The central interface is `BalanceRepository` — this is the public API exposed to other plugins via `Jecon.getRepository()`.

Two implementations:
- **`SyncRepository`** — writes directly to the database on every operation
- **`LazyRepository`** — keeps a write-back cache in memory. Balances are stored internally as `long` (cents, i.e. actual value × 100). On player login, `consistency()` syncs any dirty cached values. On logout/shutdown, `save(uuid)` / `saveAll()` flush changes as *deltas* (difference from original DB value) using SQL `UPDATE balance SET balance=balance+?`, enabling multi-server safety.

`AbstractRepository` handles all the type conversion (double/BigDecimal ↔ internal long representation) and formatting logic. Subclasses only implement `getRaw()`, `set()`, `deposit()`, and `createAccount()`.

### Database Layer

`Database` is an abstract class with two concrete drivers: `MySQL` and `SQLite`. Connection pooling is handled by HikariCP. The schema uses two tables:
- `account(id INT, uuid BYTES)` — maps UUIDs to integer IDs
- `balance(id INT, balance LONG)` — stores balance in cents (×100)

`AbstractRepository` maintains a UUID→ID cache (`uuidToIdCache`) to avoid repeated DB lookups.

### Command System

Commands use `SubExecutor` from JBukkitLib. Each subcommand (`show`, `pay`, `set`, `give`, `take`, `create`, `remove`, `top`, `convert`, `reload`, `version`) is a separate class in `jp.jyn.jecon.command`. The main command is registered as both `/jecon` and `/money`.

### Config System

`ConfigLoader` manages two config files (`config.yml` and `message.yml`). `MainConfig` and `MessageConfig` are immutable value objects populated at load time. Migration classes (`config/migration/`) handle upgrading config files from older versions.

### Vault Integration

`VaultEconomy` implements the Vault `Economy` interface. If Vault loads after Jecon, a `VaultRegister` listener waits for `PluginEnableEvent` before hooking in.

### Key Design Decisions

- Balances are stored internally as `long` (integer cents) to avoid floating-point precision issues. The public API accepts both `double` and `BigDecimal`.
- `LazyRepository` uses delta-writes to DB, making it safe for BungeeCord multi-server setups where the same player can't log in to two servers simultaneously.
- The `account` table stores UUIDs as raw bytes for compactness and MySQL compatibility.