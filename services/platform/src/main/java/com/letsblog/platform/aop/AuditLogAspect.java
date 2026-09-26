package com.letsblog.platform.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.platform.service.AuditLogService;
import com.letsblog.platform.service.CurrentActorService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * @AuditLog を付与したメソッドの正常終了を監査ログとして記録する。legacy-apiのAuditLogAspectと同じ実装
 * (issue #693)。resourceIdの抽出はリフレクションによる簡易な推定に留め、複雑なSpEL式などは扱わない。
 */
@Aspect
@Component
@Slf4j
public class AuditLogAspect {

    /** audit_logs.changes(TEXT)の上限65,535バイトに対し、余裕を持たせた上限(issue #1246)。 */
    private static final int MAX_CHANGES_BYTES = 60_000;
    private static final int PREVIEW_CHARS = 1_000;

    private final AuditLogService auditLogService;
    private final CurrentActorService currentActorService;
    private final ObjectMapper objectMapper;

    public AuditLogAspect(AuditLogService auditLogService, CurrentActorService currentActorService,
                           ObjectMapper objectMapper) {
        this.auditLogService = auditLogService;
        this.currentActorService = currentActorService;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(com.letsblog.platform.aop.AuditLog)")
    public Object auditLogAdvice(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        AuditLog annotation = method.getAnnotation(AuditLog.class);

        Object result = joinPoint.proceed();

        try {
            Long actorId = currentActorService.getCurrentActorId();
            String actorKeycloakSub = currentActorService.getCurrentActorKeycloakSub();
            Long resourceId = extractResourceId(result, joinPoint.getArgs());
            String changes = toJson(result);

            auditLogService.log(
                    actorId,
                    actorKeycloakSub,
                    annotation.action(),
                    annotation.resourceType(),
                    resourceId,
                    changes,
                    currentActorService.getRemoteIp(),
                    currentActorService.getUserAgent());
        } catch (Exception e) {
            log.warn("Failed to record audit log for {}", method.getName(), e);
        }

        return result;
    }

    private Long extractResourceId(Object result, Object[] args) {
        Long fromResult = tryExtractId(result);
        if (fromResult != null) {
            return fromResult;
        }
        for (Object arg : args) {
            if (arg instanceof Long) {
                return (Long) arg;
            }
        }
        return null;
    }

    private Long tryExtractId(Object obj) {
        if (obj == null) {
            return null;
        }
        for (String accessor : new String[]{"id", "getId"}) {
            try {
                Method idMethod = obj.getClass().getMethod(accessor);
                Object value = idMethod.invoke(obj);
                if (value instanceof Long) {
                    return (Long) value;
                }
            } catch (ReflectiveOperationException ignored) {
                // このアクセサは存在しない、または戻り値がLongでない
            }
        }
        return null;
    }

    private String toJson(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof byte[] bytes) {
            // バイナリ(バックアップZIP等)は中身を記録せず、種別とサイズだけを残す(issue #1246)
            return "{\"type\":\"binary\",\"size\":" + bytes.length + "}";
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            json = String.valueOf(result);
        }
        return limitSize(json);
    }

    /** audit_logs.changes(TEXT、最大65,535バイト)へ収まらない長さなら、有効なJSONの要約へ置き換える。 */
    private String limitSize(String json) {
        int size = json.getBytes(StandardCharsets.UTF_8).length;
        if (size <= MAX_CHANGES_BYTES) {
            return json;
        }
        int end = PREVIEW_CHARS;
        if (Character.isHighSurrogate(json.charAt(end - 1))) {
            end--;
        }
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "truncated", true, "originalSize", size, "preview", json.substring(0, end)));
        } catch (Exception e) {
            return "{\"truncated\":true,\"originalSize\":" + size + "}";
        }
    }
}
