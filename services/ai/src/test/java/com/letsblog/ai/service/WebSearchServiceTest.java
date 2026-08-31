package com.letsblog.ai.service;

import com.letsblog.ai.ai.BraveSearchClient;
import com.letsblog.ai.ai.BraveSearchResult;
import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.common.crypto.CredentialCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSearchServiceの回帰テスト(issue #184/#574)。projectId指定時はai-service自身が持つ
 * project_ai_settings(ProjectAiSettingsService)から直接キーを解決すること、未指定時/未設定時は
 * platform-serviceのシステム全体設定へ直接フォールバックすることを検証する。
 */
@ExtendWith(MockitoExtension.class)
class WebSearchServiceTest {

    @Mock
    private BraveSearchClient braveSearchClient;
    @Mock
    private PlatformServiceClient platformServiceClient;
    @Mock
    private ProjectAiSettingsService projectAiSettingsService;
    @Mock
    private CurrentActorService currentActorService;
    @Mock
    private CredentialCipher credentialCipher;

    private WebSearchService service() {
        return new WebSearchService(
                braveSearchClient, platformServiceClient, projectAiSettingsService, currentActorService,
                credentialCipher);
    }

    @Test
    void searchSafely_projectId指定時はプロジェクト自身のキー解決を使う() {
        when(projectAiSettingsService.getBraveSearchApiKeyEncrypted(1L)).thenReturn(new byte[]{1, 2, 3});
        when(credentialCipher.decrypt(new byte[]{1, 2, 3})).thenReturn("project-key");
        when(braveSearchClient.search(anyString(), anyInt(), eq("project-key")))
                .thenReturn(List.of(new BraveSearchResult("title", "desc", "https://example.com")));

        WebSearchOutcome outcome = service().searchSafely("query", 1L);

        assertTrue(outcome.succeeded());
        verify(platformServiceClient, org.mockito.Mockito.never()).resolveSystemBraveSearchApiKey(anyString());
    }

    @Test
    void searchSafely_projectIdのキー未設定ならシステム全体設定へフォールバックする() {
        when(projectAiSettingsService.getBraveSearchApiKeyEncrypted(1L)).thenReturn(null);
        when(platformServiceClient.resolveSystemBraveSearchApiKey(null)).thenReturn("system-key");
        when(braveSearchClient.search(anyString(), anyInt(), eq("system-key"))).thenReturn(List.of());

        WebSearchOutcome outcome = service().searchSafely("query", 1L);

        assertTrue(outcome.succeeded());
    }

    @Test
    void searchSafely_projectId未指定時はシステム全体設定をブリッジ経由で使う() {
        when(platformServiceClient.resolveSystemBraveSearchApiKey(null)).thenReturn("system-key");
        when(braveSearchClient.search(anyString(), anyInt(), eq("system-key"))).thenReturn(List.of());

        WebSearchOutcome outcome = service().searchSafely("query");

        assertTrue(outcome.succeeded());
    }
}
