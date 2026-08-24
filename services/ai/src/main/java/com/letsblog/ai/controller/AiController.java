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

    public AiController(AiAssistService aiAssistService) {
        this.aiAssistService = aiAssistService;
    }

    @PostMapping("/api/ai/draft")
    public AiDraftResponse draft(@Valid @RequestBody AiDraftRequest request) {
        return aiAssistService.draft(request);
    }

    /** issue #526: エディタ右クリックメニュー「Ask AI」からの質問に、Web検索結果を踏まえて回答する。 */
    @PostMapping("/api/ai/ask")
    public AiAskResponse ask(@Valid @RequestBody AiAskRequest request) {
        return aiAssistService.ask(request);
    }

    @PostMapping("/api/ai/tags")
    public AiTagsResponse tags(@Valid @RequestBody AiTagsRequest request) {
        return aiAssistService.suggestTags(request);
    }

    /** issue #523: エディタでのリアルタイム校正チェック。 */
    @PostMapping("/api/ai/proofread")
    public AiProofreadResponse proofread(@Valid @RequestBody AiProofreadRequest request) {
        return aiAssistService.proofreadContent(request);
    }

    @PostMapping("/api/ai/section")
    public AiSectionResponse section(@Valid @RequestBody AiSectionRequest request) {
        return aiAssistService.generateSection(request);
    }
}
