package com.letsblog.ai.controller;

import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.dto.GenerationJobDetailResponse;
import com.letsblog.ai.dto.GenerationJobResponse;
import com.letsblog.ai.repository.GenerationJobRepository;
import com.letsblog.ai.service.CurrentActorService;
import com.letsblog.ai.service.GenerationJobNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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
 *
 * <p>issue #1406: ジョブは所有者(owner_user_id)を持ち、一覧・詳細は呼び出し元が起こしたジョブだけを
 * 返す。管理者は全件(所有者不明の既存行を含む)を見られる。
 */
@RestController
@RequestMapping("/api/generation-jobs")
public class GenerationJobController {

    private final GenerationJobRepository generationJobRepository;
    private final CurrentActorService currentActorService;

    public GenerationJobController(
            GenerationJobRepository generationJobRepository, CurrentActorService currentActorService) {
        this.generationJobRepository = generationJobRepository;
        this.currentActorService = currentActorService;
    }

    /**
     * 呼び出し元が起こしたジョブだけを返す(issue #1406。AIキューは利用者ごとに分ける、という決定)。
     * 管理者は全件を見られ、これには所有者不明(NULL)の既存行も含まれる。
     * 操作者を解決できない一般利用者には何も返さない。
     *
     * <p>認可不要: 403で拒否せず、所有者による絞り込みで見える範囲が決まる(認証済みなら誰でも呼べる。
     * ログイン後の共通ダッシュボード、issue #830)。所有者は identity-service のユーザーID
     * ({@link CurrentActorService#getCurrentActorId()})。
     */
    @GetMapping
    public List<GenerationJobResponse> list() {
        // generation_jobs.created_atは秒精度(V1__create_ai_tables.sqlのDATETIME列)のため、
        // createdAtだけでは同一秒に作られた複数ジョブが同値になり、安定ソートの結果
        // findAll()のDB取得順(通常ID昇順)がそのまま残って「新しい順」の契約が崩れる
        // (issue #1332、#934/#1147のシナリオが同一秒の別ジョブを誤って拾った実例)。
        // IDはAUTO_INCREMENTで単調増加するため、第2キーの降順タイブレークに使うことで、
        // createdAtの精度に関わらず作成順を一意に決定できる。
        return visibleJobs().stream()
                .sorted(Comparator.comparing(GenerationJob::getCreatedAt)
                        .thenComparing(GenerationJob::getId)
                        .reversed())
                .map(job -> new GenerationJobResponse(
                        job.getId(), job.getType(), job.getStatus(), job.getCreatedAt(), job.getUpdatedAt()))
                .toList();
    }

    /**
     * モデルインストール等の非同期ジョブの完了をフロントがポーリングするためのエンドポイント。
     *
     * <p>認可不要: {@link #list}と同じ理由(絞り込みで決まる。issue #1406)。
     * 他人のジョブ(と、一般利用者から見た所有者不明の既存行)は、存在しない場合と同じ
     * {@link GenerationJobNotFoundException}(404)にする。403にするとID の存在が漏れる。
     * 管理者はどのジョブも取得できる(issue #1406)。
     */
    @GetMapping("/{id}")
    public GenerationJobDetailResponse get(@PathVariable Long id) {
        GenerationJob job = generationJobRepository.findById(id)
                .filter(this::isVisible)
                .orElseThrow(() -> new GenerationJobNotFoundException("id " + id + " のジョブは見つかりません"));
        return new GenerationJobDetailResponse(
                job.getId(), job.getType(), job.getStatus(),
                job.getRequestPayload(), job.getResultPayload(),
                job.getCreatedAt(), job.getUpdatedAt());
    }

    private List<GenerationJob> visibleJobs() {
        if (currentActorService.isAdmin()) {
            return generationJobRepository.findAll();
        }
        Long actorId = currentActorService.getCurrentActorId();
        return actorId == null ? List.of() : generationJobRepository.findByOwnerUserId(actorId);
    }

    private boolean isVisible(GenerationJob job) {
        if (currentActorService.isAdmin()) {
            return true;
        }
        Long actorId = currentActorService.getCurrentActorId();
        return actorId != null && actorId.equals(job.getOwnerUserId());
    }
}
