package com.letsblog.content.aop;

import com.letsblog.content.domain.AuditLogAction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 付与したメソッドの正常終了を監査ログとして記録する。legacy-apiのAuditLogアノテーションと同じ実装。
 * resourceIdは戻り値(idまたはgetIdを持つ場合)、無ければ引数中の最初のLong値から推定する。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {
    AuditLogAction action();

    String resourceType() default "";
}
