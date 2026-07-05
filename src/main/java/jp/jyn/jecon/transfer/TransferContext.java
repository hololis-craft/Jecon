package jp.jyn.jecon.transfer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 振替のドメイン context。source（呼び出し理由）と metadata、actor、overdraft フラグを保持する。
 *
 * <p>04-context-and-log.md に沿う。source は呼び出し側が付ける短い識別子。
 * metadata は JSON 相当の Map で、監査ログの {@code metadata} 列にシリアライズされる。
 */
public final class TransferContext {
    private final String source;
    private final Map<String, String> metadata;
    private final UUID actor;
    private final boolean overdraft;

    private TransferContext(String source, Map<String, String> metadata, UUID actor, boolean overdraft) {
        this.source = source;
        this.metadata = metadata;
        this.actor = actor;
        this.overdraft = overdraft;
    }

    public String source() {
        return source;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public UUID actor() {
        return actor;
    }

    public boolean overdraft() {
        return overdraft;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TransferContext of(String source) {
        return builder().source(source).build();
    }

    public static final class Builder {
        private String source;
        private final Map<String, String> metadata = new LinkedHashMap<>();
        private UUID actor;
        private boolean overdraft;

        private Builder() {}

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder metadata(String key, String value) {
            if (key != null && value != null) {
                this.metadata.put(key, value);
            }
            return this;
        }

        public Builder metadata(Map<String, String> map) {
            if (map != null) {
                map.forEach(this::metadata);
            }
            return this;
        }

        public Builder actor(UUID uuid) {
            this.actor = uuid;
            return this;
        }

        public Builder withOverdraft() {
            this.overdraft = true;
            return this;
        }

        public TransferContext build() {
            if (source == null || source.isEmpty()) {
                throw new IllegalStateException("TransferContext.source must be set");
            }
            return new TransferContext(source, Collections.unmodifiableMap(new LinkedHashMap<>(metadata)), actor, overdraft);
        }
    }
}
