package com.letsblog.platform.controller;

import com.letsblog.platform.service.PluginRegistrationService;
import com.letsblog.platform.service.PluginRegistrationService.ResolvedRegistration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

/** 内部の解決API(issue #1807)。種類を指定して使用中の登録(または未接続)を返す。 */
@ExtendWith(MockitoExtension.class)
class InternalPluginRegistrationControllerTest {

    @Mock
    private PluginRegistrationService service;

    @Test
    void 種類を指定して解決をサービスへ委譲する() {
        ResolvedRegistration resolved = new ResolvedRegistration(false, null, null, null, null, null);
        when(service.resolve("LLM")).thenReturn(resolved);

        assertSame(resolved, new InternalPluginRegistrationController(service).resolve("LLM"));
    }
}
