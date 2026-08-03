package jp.jyn.jecon.testing;

import org.junit.jupiter.api.condition.EnabledIf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Docker が使えない環境では skip する。
 *
 * <p>CI では {@code -Djecon.test.mysql=true} を指定して、Docker が無いことによる
 * 暗黙の skip（= MySQL 経路が実は検証されていない状態）を防ぐ。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@EnabledIf(
    value = "jp.jyn.jecon.testing.MySqlBackend#isAvailable",
    disabledReason = "Docker is not available (pass -Djecon.test.mysql=true to require MySQL tests)"
)
public @interface RequiresMySql {
}
