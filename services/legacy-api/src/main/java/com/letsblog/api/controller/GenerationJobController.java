package com.letsblog.api.controller;

import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.dto.GenerationJobDetailResponse;
import com.letsblog.api.dto.GenerationJobResponse;
import com.letsblog.api.dto.UpdateGenerationJobRequest;
import com.letsblog.api.repository.GenerationJobRepository;
import com.letsblog.api.service.GenerationJobNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * LLM/ComfyUI呼び出しジョブの履歴一覧(Web管理フロントエンドの表示用)。
 * ジョブの作成自体は06-ollama-integration / 07-comfyui-integration 側で行う。
 *
 * <p>GenerationJob自体はissue #573でmedia-serviceへは移設していない(generation_jobsテーブルは
 * ComfyUiModelService/MediaGarbageCollectionServiceに加え、AiAssistService/ArticlePlanService
 * (LLM機能、legacy-apiに残る)も書き込む共有インフラのため)。media-service側の非同期ジョブランナー
 * (ModelInstallJobRunner等)は、ジョブ自体の作成はlegacy-api側(呼び出し元の同期リクエスト内)に
 * 任せ、進捗・完了・失敗の反映のみをこの{@link #update}経由で行う(#573 stage2)。
 */
@RestController
@RequestMapping("/api/generation-jobs")
public class GenerationJobController {

    private final GenerationJobRepository generationJobRepository;

    public GenerationJobController(GenerationJobRepository generationJobRepository) {
        this.generationJobRepository = generationJobRepository;
    }

    @GetMapping
    public List<GenerationJobResponse> list() {
        return generationJobRepository.findAll().stream()
                .sorted(Comparator.comparing(GenerationJob::getCreatedAt).reversed())
                .map(job -> new GenerationJobResponse(
                        job.getId(), job.getType(), job.getStatus(), job.getCreatedAt(), job.getUpdatedAt()))
                .toList();
    }

    /**
     * モデルインストール等の非同期ジョブの完了をフロントがポーリングするためのエンドポイント。
     */
    @GetMapping("/{id}")
    public GenerationJobDetailResponse get(@PathVariable Long id) {
        GenerationJob job = generationJobRepository.findById(id)
                .orElseThrow(() -> new GenerationJobNotFoundException("id " + id + " のジョブは見つかりません"));
        return new GenerationJobDetailResponse(
                job.getId(), job.getType(), job.getStatus(),
                job.getRequestPayload(), job.getResultPayload(),
                job.getCreatedAt(), job.getUpdatedAt());
    }

    /**
     * media-service側の非同期ジョブランナーが、自身が実行しているジョブの進捗・完了・失敗を
     * 反映するために呼ぶ(#573 stage2)。statusはこのAPI自体では値の妥当性を検証しない
     * (呼び出し元が"running"/"done"/"failed"等、既存の慣例に従う責務を負う)。
     */
    @PatchMapping("/{id}")
    public GenerationJobResponse update(@PathVariable Long id, @RequestBody UpdateGenerationJobRequest request) {
        GenerationJob job = generationJobRepository.findById(id)
                .orElseThrow(() -> new GenerationJobNotFoundException("id " + id + " のジョブは見つかりません"));
        job.setStatus(request.status());
        job.setResultPayload(request.resultPayload());
        GenerationJob saved = generationJobRepository.save(job);
        return new GenerationJobResponse(
                saved.getId(), saved.getType(), saved.getStatus(), saved.getCreatedAt(), saved.getUpdatedAt());
    }
}
