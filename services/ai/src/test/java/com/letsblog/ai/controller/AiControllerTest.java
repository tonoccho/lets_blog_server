package com.letsblog.ai.controller;

import com.letsblog.ai.domain.ReviewStepKey;
import com.letsblog.ai.dto.AiProofreadRequest;
import com.letsblog.ai.dto.AiReviewStepSuggestionsRequest;
import com.letsblog.ai.dto.AiReviewStepSuggestionsResponse;
import com.letsblog.ai.dto.AiTagsRequest;
import com.letsblog.ai.dto.AiTagsResponse;
import com.letsblog.ai.dto.AiProofreadResponse;
import com.letsblog.ai.dto.ProofreadIssue;
import com.letsblog.ai.dto.ReviewStepSuggestion;
import com.letsblog.ai.service.AdminAuthorizationService;
import com.letsblog.ai.service.AiAssistService;
import com.letsblog.ai.service.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AiControllerの回帰テスト(issue #574)。legacy-apiに残った画像生成関連エンドポイント
 * (generateImagePrompt/image/image-options)を除き、テキスト生成系エンドポイントがAiAssistServiceへ
 * 委譲することを検証する。
 */
@ExtendWith(MockitoExtension.class)
class AiControllerTest {

    @Mock
    private AiAssistService aiAssistService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private AiController controller() {
        return new AiController(aiAssistService, adminAuthorizationService);
    }

    @Test
    void proofread_サービスへ委譲する() {
        AiController controller = controller();
        AiProofreadRequest request = new AiProofreadRequest("記事本文", null);
        AiProofreadResponse expected = new AiProofreadResponse(
                List.of(new ProofreadIssue("typo", "誤字", "指摘内容", "修正案")));
        when(aiAssistService.proofreadContent(request)).thenReturn(expected);

        AiProofreadResponse response = controller.proofread(request);

        assertEquals(expected, response);
    }

    // ---- issue #830 ----

    @Test
    void tags_projectId指定時はプロジェクトメンバー判定を通す() {
        // projectIdが指定されると既存タグ(保存済みリソース)を読むため、他の生成系と違い認可が要る。
        AiTagsRequest request = new AiTagsRequest("本文", null, 7L);
        when(aiAssistService.suggestTags(request)).thenReturn(new AiTagsResponse(List.of("カテゴリ"), List.of("タグ")));

        controller().tags(request);

        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
    }

    @Test
    void tags_プロジェクトメンバーでなければ生成せずに拒否する() {
        AiTagsRequest request = new AiTagsRequest("本文", null, 7L);
        doThrow(new ForbiddenException("この操作にはプロジェクトメンバーまたはadmin権限が必要です"))
                .when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        assertThrows(ForbiddenException.class, () -> controller().tags(request));

        verifyNoInteractions(aiAssistService);
    }

    @Test
    void tags_projectId未指定なら読むものが無いので認可判定しない() {
        AiTagsRequest request = new AiTagsRequest("本文", null, null);
        when(aiAssistService.suggestTags(request)).thenReturn(new AiTagsResponse(List.of("カテゴリ"), List.of("タグ")));

        controller().tags(request);

        verify(adminAuthorizationService, never()).requireProjectMemberOrAdmin(org.mockito.ArgumentMatchers.anyLong());
    }

    // ---- issue #1213: レビューステップ単位の指摘生成 ----

    @Test
    void reviewStepSuggestions_プロジェクトメンバー判定を通してサービスへ委譲する() {
        AiReviewStepSuggestionsRequest request = new AiReviewStepSuggestionsRequest("本文");
        AiReviewStepSuggestionsResponse expected = new AiReviewStepSuggestionsResponse(
                List.of(new ReviewStepSuggestion("id1", "JAPANESE", "本文", "指摘")));
        when(aiAssistService.generateReviewStepSuggestions(7L, ReviewStepKey.JAPANESE, "本文")).thenReturn(expected);

        AiReviewStepSuggestionsResponse response =
                controller().reviewStepSuggestions(7L, ReviewStepKey.JAPANESE, request);

        assertEquals(expected, response);
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
    }

    @Test
    void reviewStepSuggestions_プロジェクトメンバーでなければ生成せずに拒否する() {
        AiReviewStepSuggestionsRequest request = new AiReviewStepSuggestionsRequest("本文");
        doThrow(new ForbiddenException("この操作にはプロジェクトメンバーまたはadmin権限が必要です"))
                .when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        assertThrows(ForbiddenException.class,
                () -> controller().reviewStepSuggestions(7L, ReviewStepKey.JAPANESE, request));

        verifyNoInteractions(aiAssistService);
    }
}
