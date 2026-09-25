package com.letsblog.ai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.service.CurrentActorService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** provider別の選択可能モデル一覧の解決(issue #1088)。 */
class RemoteLlmConfigProviderTest {

    private final PlatformServiceClient client = mock(PlatformServiceClient.class);
    private final CurrentActorService actor = mock(CurrentActorService.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final RemoteLlmConfigProvider provider = new RemoteLlmConfigProvider(client, actor, request);

    @Test
    void availableModelsFor_指定providerで解決した一覧を返す() {
        when(actor.getAuthorizationHeader()).thenReturn("Bearer t");
        when(client.resolveLlmConfig("OLLAMA", "Bearer t")).thenReturn(
                new PlatformServiceClient.LlmConfig("OLLAMA", "u", "", "qwen", List.of("qwen"), 10L));

        assertEquals(List.of("qwen"), provider.availableModelsFor(AiProvider.OLLAMA));
    }

    @Test
    void availableModelsFor_同一リクエスト内では往復を増やさない() {
        when(actor.getAuthorizationHeader()).thenReturn("Bearer t");
        when(client.resolveLlmConfig("CLAUDE", "Bearer t")).thenReturn(
                new PlatformServiceClient.LlmConfig("CLAUDE", "u", "k", "c", List.of("c"), 10L));

        provider.availableModelsFor(AiProvider.CLAUDE);
        provider.availableModelsFor(AiProvider.CLAUDE);

        verify(client, times(1)).resolveLlmConfig("CLAUDE", "Bearer t");
    }
}
