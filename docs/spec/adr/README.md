# ADR 索引

Economy プラグインの設計判断記録。

## 一覧

- [ADR-0001 既存 Jecon の fork をベースにする](./0001-fork-jecon-as-base.md)
- [ADR-0002 AccountRef を単一テーブルに載せる](./0002-account-ref-single-table.md)（置き換え → ADR-0013）
- [ADR-0003 namespace は任意文字列を許容する](./0003-arbitrary-namespace-strings.md)
- [ADR-0004 transfer を一級 API にする](./0004-transfer-as-primary-api.md)
- [ADR-0005 Modifier は外部登録のみで、Economy 本体に built-in を持たない](./0005-transfer-modifier-external-only.md)
- [ADR-0006 Vault 経由の振替も Modifier pipeline を通す](./0006-vault-through-modifier-pipeline.md)
- [ADR-0007 overdraft オプションを持たせる](./0007-allow-overdraft-optional.md)
- [ADR-0008 transaction context に source と metadata を持たせる](./0008-transaction-context-source-and-metadata.md)
- [ADR-0009 Lazy モード下の transfer 意味論](./0009-lazy-repository-transfer-semantics.md)（置き換え → ADR-0012）
- [ADR-0010 BalanceRepository の後方互換](./0010-backward-compat-balancerepository.md)
- [ADR-0011 VaultUnlockedAPI を採用し shared account を非 Player 口座に写像、Async は実装しない](./0011-vaultunlocked-shared-account-no-async.md)
- [ADR-0012 LazyRepository を廃止し Sync 単一モードにする](./0012-drop-lazy-repository.md)
- [ADR-0013 口座の主キーを UUID とし alias を副次表現とする](./0013-uuid-primary-alias-secondary.md)
- [ADR-0014 書き込み経路をスレッドセーフにし、任意のスレッドから呼べるようにする](./0014-thread-safe-write-path.md)

## ステータスの読み方

- **受け入れ**：採用された判断。実装または運用の根拠となる。
- **置き換え**：別の ADR で覆された判断。元の ADR は履歴として残す。
- **保留**：判断を先送りにしている。後続 ADR で確定させる。
