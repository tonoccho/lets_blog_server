package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.BraveSearchResult;
import com.letsblog.api.ai.ComfyUiClient;
import com.letsblog.api.ai.GeneratedImageStorageService;
import com.letsblog.api.ai.OllamaClient;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.dto.AiDraftRequest;
import com.letsblog.api.dto.AiDraftResponse;
import com.letsblog.api.dto.AiSectionRequest;
import com.letsblog.api.dto.AiSectionResponse;
import com.letsblog.api.repository.GeneratedImageRepository;
import com.letsblog.api.repository.GenerationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AiAssistServiceの回帰テスト。draft()/generateSection()へのBrave出典統合
 * (検索成功/失敗/0件それぞれでsources・searchNoteが期待通りになること)を中心に検証する。
 */
@ExtendWith(MockitoExtension.class)
class AiAssistServiceTest {

    @Mock
    private OllamaClient ollamaClient;
    @Mock
    private ComfyUiClient comfyUiClient;
    @Mock
    private ComfyUiModelService comfyUiModelService;
    @Mock
    private GeneratedImageStorageService generatedImageStorageService;
    @Mock
    private GeneratedImageRepository generatedImageRepository;
    @Mock
    private GenerationJobRepository generationJobRepository;
    @Mock
    private WebSearchService webSearchService;

    private AiAssistService service;

    @BeforeEach
    void setUp() {
        service = new AiAssistService(ollamaClient, comfyUiClient, comfyUiModelService,
                generatedImageStorageService, generatedImageRepository, generationJobRepository,
                webSearchService, new ObjectMapper());

        lenient().when(generationJobRepository.save(any())).thenAnswer(inv -> {
            GenerationJob job = inv.getArgument(0);
            if (job.getId() == null) {
                job.setId(1L);
            }
            return job;
        });
    }

    @Test
    void draft_検索成功時はsourcesを含み検索結果をプロンプトへ付加する() {
        when(webSearchService.searchSafely(anyString())).thenReturn(
                WebSearchOutcome.success(List.of(new BraveSearchResult("Title", "Desc", "https://example.com"))));
        when(ollamaClient.generate(anyString())).thenReturn("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて"));

        assertEquals("生成結果", response.result());
        assertEquals(1, response.sources().size());
        assertEquals("https://example.com", response.sources().get(0).url());
        assertNull(response.searchNote());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ollamaClient).generate(promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("参考のWeb検索結果"));
    }

    @Test
    void draft_検索失敗時はsourcesが空でsearchNoteが設定される() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("APIキー未設定"));
        when(ollamaClient.generate(anyString())).thenReturn("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて"));

        assertEquals(List.of(), response.sources());
        assertEquals("Web検索を利用できなかったため、出典なしで生成しています", response.searchNote());
    }

    @Test
    void draft_検索成功だが0件の場合はその旨のsearchNoteになる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.success(List.of()));
        when(ollamaClient.generate(anyString())).thenReturn("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて"));

        assertEquals(List.of(), response.sources());
        assertEquals("関連する検索結果が見つかりませんでした", response.searchNote());
    }

    @Test
    void draft_不正なmodeは例外() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.draft(new AiDraftRequest("invalid", "text")));
    }

    @Test
    void generateSection_本文モードは直前の文脈を含むプロンプトを組み立てる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(ollamaClient.generate(anyString())).thenReturn("セクション本文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", "導入部", "前の段落の文脈", "記事タイトル"));

        assertEquals("セクション本文", response.result());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ollamaClient).generate(promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("前の段落の文脈"));
        assertTrue(promptCaptor.getValue().contains("記事タイトル"));
        assertTrue(promptCaptor.getValue().contains("導入部"));
    }

    @Test
    void generateSection_リード文モードは出典を含めて返す() {
        when(webSearchService.searchSafely(anyString())).thenReturn(
                WebSearchOutcome.success(List.of(new BraveSearchResult("Title", "Desc", "https://example.com"))));
        when(ollamaClient.generate(anyString())).thenReturn("リード文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("lead", "はじめに", null, "記事タイトル"));

        assertEquals("リード文", response.result());
        assertEquals(1, response.sources().size());
        assertNull(response.searchNote());
    }

    @Test
    void generateSection_不正なmodeは例外() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.generateSection(new AiSectionRequest("invalid", "見出し", null, null)));
    }
}
