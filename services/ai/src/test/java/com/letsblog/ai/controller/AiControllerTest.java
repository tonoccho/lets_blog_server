package com.letsblog.ai.controller;

import com.letsblog.ai.domain.ReviewStepKey;
import com.letsblog.ai.dto.AiProofreadRequest;
import com.letsblog.ai.dto.AiProofreadResponse;
import com.letsblog.ai.dto.AiReviewStepSuggestionsRequest;
import com.letsblog.ai.dto.AiReviewStepSuggestionsResponse;
import com.letsblog.ai.dto.AiTagsRequest;
import com.letsblog.ai.dto.AiTagsResponse;
import com.letsblog.ai.service.ForbiddenException;
import com.letsblog.ai.testsupport.AiWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiControllerの回帰テスト(issue #574)。legacy-apiに残った画像生成関連エンドポイント
 * (generateImagePrompt/image/image-options)を除き、テキスト生成系エンドポイントがAiAssistServiceへ
 * 委譲することを検証する。
 *
 * <p>issue #1824: AiAssistService・AdminAuthorizationServiceなどは実物で、LLM・identity-serviceはHTTPフェイク、
 * DBはメモリ上のリポジトリで差し替える。
 */
class AiControllerTest {

    private AiWorld world;
    private AiController controller;

    @BeforeEach
    void setUp() {
        world = new AiWorld().ollamaDefault("qwen2.5:7b-instruct");
        controller = new AiController(world.aiAssistService, world.adminAuthorizationService);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private void nonMember(long userId, long projectId) {
        world.member(userId);
        world.http.json("GET", "/api/internal/identity/projects/" + projectId + "/members/" + userId, "false");
    }

    @Test
    void proofread_サービスへ委譲する() {
        world.admin();
        world.llmReplies("[{\"type\": \"typo\", \"originalText\": \"記事\", \"message\": \"指摘内容\", \"suggestion\": \"修正案\"}]");

        AiProofreadResponse response = controller.proofread(new AiProofreadRequest("記事本文", null, null));

        assertEquals(1, response.issues().size());
        assertEquals("typo", response.issues().get(0).type());
        assertEquals("記事", response.issues().get(0).originalText());
        assertEquals("指摘内容", response.issues().get(0).message());
        assertEquals("修正案", response.issues().get(0).suggestion());
        assertTrue(world.llmPrompts().get(0).contains("記事本文"));
    }

    // ---- issue #830 ----

    @Test
    void tags_projectId指定時はプロジェクトメンバー判定を通す() {
        // projectIdが指定されると既存タグ(保存済みリソース)を読むため、他の生成系と違い認可が要る。
        world.member(10L);
        world.http.json("GET", "/api/internal/identity/projects/7/members/10", "true");
        world.http.json("GET", "/api/internal/ai/projects/7/existing-tags", "[\"Java\"]");
        world.llmReplies("{\"categories\": [\"カテゴリ\"], \"tags\": [\"タグ\"]}");

        AiTagsResponse response = controller.tags(new AiTagsRequest("本文", null, 7L));

        assertEquals(List.of("カテゴリ"), response.categories());
        assertEquals(List.of("タグ"), response.tags());
        assertEquals(1, world.http.requests("GET", "/api/internal/identity/projects/7/members/10").size());
        assertEquals("Bearer t", world.http.requests("GET", "/api/internal/identity/projects/7/members/10").get(0)
                .header("Authorization"));
    }

    @Test
    void tags_プロジェクトメンバーでなければ生成せずに拒否する() {
        nonMember(10L, 7L);

        assertThrows(ForbiddenException.class, () -> controller.tags(new AiTagsRequest("本文", null, 7L)));

        assertTrue(world.llmRequests().isEmpty());
        assertTrue(world.jobRows.saved.isEmpty());
    }

    @Test
    void tags_projectId未指定なら読むものが無いので認可判定しない() {
        world.llmReplies("{\"categories\": [\"カテゴリ\"], \"tags\": [\"タグ\"]}");

        AiTagsResponse response = controller.tags(new AiTagsRequest("本文", null, null));

        assertEquals(List.of("タグ"), response.tags());
        // 認可判定をしないので、identity-serviceへは一度も問い合わせない。
        assertTrue(world.http.requests("GET", "/api/(internal/)?identity/.*").isEmpty());
    }

    // ---- issue #1213: レビューステップ単位の指摘生成 ----

    @Test
    void reviewStepSuggestions_プロジェクトメンバー判定を通してサービスへ委譲する() {
        // issue #1222レビュー(2026-09-28)対応: ステップキーの検証(未知の値の400化とgeneration_jobs
        // への失敗記録)をコントローラからAiAssistServiceへ移したため、委譲先はenumではなく
        // パス変数の生のStringを受け取るオーバーロードになった。
        world.member(10L);
        world.http.json("GET", "/api/internal/identity/projects/7/members/10", "true");
        world.llmReplies("[{\"originalText\": \"ら抜き言葉の例\", \"message\": \"指摘\"}]");

        AiReviewStepSuggestionsResponse response = controller.reviewStepSuggestions(
                7L, ReviewStepKey.JAPANESE.name(), new AiReviewStepSuggestionsRequest("これはら抜き言葉の例です"));

        assertEquals(1, response.suggestions().size());
        assertEquals("JAPANESE", response.suggestions().get(0).stepKey());
        assertEquals("ら抜き言葉の例", response.suggestions().get(0).originalText());
        assertEquals("指摘", response.suggestions().get(0).message());
        assertEquals(1, world.http.requests("GET", "/api/internal/identity/projects/7/members/10").size());
    }

    @Test
    void reviewStepSuggestions_プロジェクトメンバーでなければ生成せずに拒否する() {
        nonMember(10L, 7L);

        assertThrows(ForbiddenException.class, () -> controller.reviewStepSuggestions(
                7L, ReviewStepKey.JAPANESE.name(), new AiReviewStepSuggestionsRequest("本文")));

        assertTrue(world.llmRequests().isEmpty());
        assertTrue(world.jobRows.saved.isEmpty());
    }
}
