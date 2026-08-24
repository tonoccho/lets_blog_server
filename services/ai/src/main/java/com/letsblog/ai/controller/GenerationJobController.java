package com.letsblog.ai.controller;

import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.dto.CreateGenerationJobRequest;
import com.letsblog.ai.dto.GenerationJobDetailResponse;
import com.letsblog.ai.dto.GenerationJobResponse;
import com.letsblog.ai.dto.UpdateGenerationJobRequest;
import com.letsblog.ai.repository.GenerationJobRepository;
import com.letsblog.ai.service.GenerationJobNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * LLM/ComfyUI呼び出しジョブの履歴一覧(Web管理フロントエンドの表示用)。
 *
 * <p>generation_jobsテーブルはLLMジョブ(AiAssistService/ArticlePlanService、ai-serviceが
 * 直接書き込む)とComfyUIジョブ(ComfyUiModelService/MediaGarbageCollectionService、
 * media-serviceが#573で導入したGenerationJobClient経由でこのコントローラを叩く)の共有インフラ
 * だったため、#573時点ではlegacy-apiがオーナーだった。issue #574でai-serviceへ移設し、
 * media-serviceのGenerationJobClientの向き先もlegacy-apiからai-serviceへ変更した。
 * media-service側の非同期ジョブランナー(ModelInstallJobRunner等)は、ジョブ自体の作成は
 * 呼び出し元(ProjectAiModelController等、legacy-api)の同期リクエスト内に任せ、
 * 進捗・完了・失敗の反映のみをこの{@link #update}経由で行う(#573 stage2)。
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
     * media-service側で、legacy-apiに残らなくなったコントローラ(ProjectMediaGarbageCollectionController
     * 等)からジョブを起動するために呼ぶ(#573 stage3)。作成直後のstatusは常に"running"
     * (既存のComfyUiModelService/MediaGarbageCollectionServiceの挙動を踏襲)。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GenerationJobResponse create(@RequestBody CreateGenerationJobRequest request) {
        GenerationJob job = new GenerationJob();
        job.setType(request.type());
        job.setStatus("running");
        job.setRequestPayload(request.requestPayload());
        GenerationJob saved = generationJobRepository.save(job);
        return new GenerationJobResponse(
                saved.getId(), saved.getType(), saved.getStatus(), saved.getCreatedAt(), saved.getUpdatedAt());
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
