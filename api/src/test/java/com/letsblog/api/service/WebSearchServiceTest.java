package com.letsblog.api.service;

import com.letsblog.api.ai.BraveSearchClient;
import com.letsblog.api.ai.BraveSearchResult;
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
 * WebSearchServiceの回帰テスト(issue #184)。projectId指定時にプロジェクトのBrave APIキー解決
 * (フォールバック込み)を経由すること、未指定時はシステム全体設定を直接使うことを検証する。
 */
@ExtendWith(MockitoExtension.class)
class WebSearchServiceTest {

    @Mock
    private BraveSearchClient braveSearchClient;
    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private ProjectApiKeyService projectApiKeyService;

    private WebSearchService service() {
        return new WebSearchService(braveSearchClient, systemSettingService, projectApiKeyService);
    }

    @Test
    void searchSafely_projectId指定時はプロジェクトのキー解決を使う() {
        when(projectApiKeyService.resolveBraveSearchApiKey(1L)).thenReturn("project-key");
        when(braveSearchClient.search(anyString(), anyInt(), eq("project-key")))
                .thenReturn(List.of(new BraveSearchResult("title", "desc", "https://example.com")));

        WebSearchOutcome outcome = service().searchSafely("query", 1L);

        assertTrue(outcome.succeeded());
        verify(projectApiKeyService).resolveBraveSearchApiKey(1L);
    }

    @Test
    void searchSafely_projectId未指定時はシステム全体設定を直接使う() {
        when(systemSettingService.getBraveSearchApiKey()).thenReturn("system-key");
        when(braveSearchClient.search(anyString(), anyInt(), eq("system-key"))).thenReturn(List.of());

        WebSearchOutcome outcome = service().searchSafely("query");

        assertTrue(outcome.succeeded());
    }
}
