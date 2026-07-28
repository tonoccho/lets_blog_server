package com.letsblog.api.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.service.AuditLogService;
import com.letsblog.api.service.CurrentActorService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogAspectTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CurrentActorService currentActorService;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    private AuditLogAspect aspect;

    record SampleResult(Long id, String name) {
    }

    interface SampleTarget {
        @AuditLog(action = AuditLogAction.USER_CREATED, resourceType = "USER")
        SampleResult sampleMethod();

        @AuditLog(action = AuditLogAction.USER_DELETED, resourceType = "USER")
        void deleteMethod(Long id);
    }

    @BeforeEach
    void setUp() {
        aspect = new AuditLogAspect(auditLogService, currentActorService, new ObjectMapper());
        when(joinPoint.getSignature()).thenReturn(methodSignature);
    }

    @Test
    void 戻り値のidからresourceIdを抽出して記録する() throws Throwable {
        Method method = SampleTarget.class.getMethod("sampleMethod");
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.proceed()).thenReturn(new SampleResult(42L, "test"));
        when(joinPoint.getArgs()).thenReturn(new Object[]{});
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        when(currentActorService.getRemoteIp()).thenReturn("127.0.0.1");
        when(currentActorService.getUserAgent()).thenReturn("agent");

        Object result = aspect.auditLogAdvice(joinPoint);

        assertEquals(new SampleResult(42L, "test"), result);
        verify(auditLogService).log(eq(10L), eq(AuditLogAction.USER_CREATED), eq("USER"), eq(42L), any(), eq("127.0.0.1"), eq("agent"));
    }

    @Test
    void 戻り値がvoidの場合は引数のLongからresourceIdを抽出する() throws Throwable {
        Method method = SampleTarget.class.getMethod("deleteMethod", Long.class);
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.proceed()).thenReturn(null);
        when(joinPoint.getArgs()).thenReturn(new Object[]{99L});
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        when(currentActorService.getRemoteIp()).thenReturn("127.0.0.1");
        when(currentActorService.getUserAgent()).thenReturn("agent");

        aspect.auditLogAdvice(joinPoint);

        verify(auditLogService).log(eq(10L), eq(AuditLogAction.USER_DELETED), eq("USER"), eq(99L), any(), any(), any());
    }

    @Test
    void 監査ログ記録が失敗しても元の戻り値を返す() throws Throwable {
        Method method = SampleTarget.class.getMethod("sampleMethod");
        when(methodSignature.getMethod()).thenReturn(method);
        SampleResult expected = new SampleResult(1L, "x");
        when(joinPoint.proceed()).thenReturn(expected);
        when(joinPoint.getArgs()).thenReturn(new Object[]{});
        when(currentActorService.getCurrentActorId()).thenReturn(1L);
        when(currentActorService.getRemoteIp()).thenReturn("ip");
        when(currentActorService.getUserAgent()).thenReturn("ua");
        doThrow(new RuntimeException("db down"))
                .when(auditLogService).log(any(), any(), any(), any(), any(), any(), any());

        Object result = aspect.auditLogAdvice(joinPoint);

        assertEquals(expected, result);
    }
}
