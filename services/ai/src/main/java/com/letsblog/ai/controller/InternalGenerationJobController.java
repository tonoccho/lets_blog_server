package com.letsblog.ai.controller;

import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.dto.CreateGenerationJobRequest;
import com.letsblog.ai.dto.GenerationJobResponse;
import com.letsblog.ai.dto.UpdateGenerationJobRequest;
import com.letsblog.ai.repository.GenerationJobRepository;
import com.letsblog.ai.service.GenerationJobNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * generation_jobsの作成・更新を受ける内部ブリッジ(issue #830)。
 *
 * <p>元は{@link GenerationJobController}が{@code /api/generation-jobs}配下で一覧・詳細と
 * まとめて公開していた。しかし作成({@code POST})と更新({@code PATCH})の呼び出し元は
 * legacy-apiの{@code GenerationJobClient}とmedia-serviceの非同期ジョブランナーだけで、
 * いずれもコンテナ間で直接呼ぶ。にもかかわらず{@code /api/generation-jobs/**}はgatewayの
 * ルート表に載っている(一覧・詳細をWebが使うため)ので、<b>有効なJWTさえあれば外部から
 * 任意のジョブを作成・改変できる</b>状態だった。
 *
 * <p>gatewayは{@code /api/internal/**}をルーティングしないため、この配下へ移すことで
 * 外部からの到達経路が無くなる。認可はジョブを起動する側(ProjectAiModelController等)が担う。
 */
@RestController
@RequestMapping("/api/internal/ai/generation-jobs")
public class InternalGenerationJobController {

    private final GenerationJobRepository generationJobRepository;

    public InternalGenerationJobController(GenerationJobRepository generationJobRepository) {
        this.generationJobRepository = generationJobRepository;
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
