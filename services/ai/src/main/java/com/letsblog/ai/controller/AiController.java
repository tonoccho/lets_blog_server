package com.letsblog.ai.controller;

import com.letsblog.ai.dto.AiAskRequest;
import com.letsblog.ai.dto.AiAskResponse;
import com.letsblog.ai.dto.AiDraftRequest;
import com.letsblog.ai.dto.AiDraftResponse;
import com.letsblog.ai.dto.AiProofreadRequest;
import com.letsblog.ai.dto.AiProofreadResponse;
import com.letsblog.ai.dto.AiSectionRequest;
import com.letsblog.ai.dto.AiSectionResponse;
import com.letsblog.ai.dto.AiTagsRequest;
import com.letsblog.ai.dto.AiTagsResponse;
import com.letsblog.ai.service.AdminAuthorizationService;
import com.letsblog.ai.service.AiAssistService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * VSCode拡張の下書き/校正/要約/タグ提案/セクション生成/Ask AIから呼ばれるテキスト生成API(issue #574)。
 * 画像生成(/api/ai/image, /api/ai/image-options, /api/projects/{id}/ai/generate-image-prompt)は
 * このIssueの移設対象外としてlegacy-apiに残る(AiAssistServiceのJavadoc参照)。
 */
@RestController
public class AiController {

    private final AiAssistService aiAssistService;
    private final AdminAuthorizationService adminAuthorizationService;

    public AiController(AiAssistService aiAssistService, AdminAuthorizationService adminAuthorizationService) {
        this.aiAssistService = aiAssistService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /**
     * 認可不要: 利用者が自分で書いた文章を渡して生成を頼むだけで、保存済みリソースには触れない
     * (issue #830)。ログイン済み利用者がAIアシスタントを使えること自体が本機能の目的。
     *
     * <p>LLMのコストは利用量に比例するが、それは認可ではなくレート制限/クォータで扱う問題として
     * 本Issueのスコープ外とした。
     */
    @PostMapping("/api/ai/draft")
    public AiDraftResponse draft(@Valid @RequestBody AiDraftRequest request) {
        return aiAssistService.draft(request);
    }

    /**
     * issue #526: エディタ右クリックメニュー「Ask AI」からの質問に、Web検索結果を踏まえて回答する。
     *
     * <p>認可不要: {@link #draft}と同じ理由(利用者自身の入力からの生成、issue #830)。
     */
    @PostMapping("/api/ai/ask")
    public AiAskResponse ask(@Valid @RequestBody AiAskRequest request) {
        return aiAssistService.ask(request);
    }

    /**
     * タグ候補を提案する。projectIdが指定されると、そのプロジェクトの既存タグを読んで候補に混ぜる
     * (AiAssistService#suggestTags)。他の生成系と違い<b>保存済みリソースを読む</b>ため、
     * 指定された場合はそのプロジェクトのメンバー(またはadmin)に限定する(issue #830)。
     * 未指定なら読むものが無いので、他の生成系と同じ扱いでよい。
     */
    @PostMapping("/api/ai/tags")
    public AiTagsResponse tags(@Valid @RequestBody AiTagsRequest request) {
        if (request.projectId() != null) {
            adminAuthorizationService.requireProjectMemberOrAdmin(request.projectId());
        }
        return aiAssistService.suggestTags(request);
    }

    /**
     * issue #523: エディタでのリアルタイム校正チェック。
     *
     * <p>認可不要: {@link #draft}と同じ理由(利用者自身の入力からの生成、issue #830)。
     */
    @PostMapping("/api/ai/proofread")
    public AiProofreadResponse proofread(@Valid @RequestBody AiProofreadRequest request) {
        return aiAssistService.proofreadContent(request);
    }

    /** 認可不要: {@link #draft}と同じ理由(利用者自身の入力からの生成、issue #830)。 */
    @PostMapping("/api/ai/section")
    public AiSectionResponse section(@Valid @RequestBody AiSectionRequest request) {
        return aiAssistService.generateSection(request);
    }
}
