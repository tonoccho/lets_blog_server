package com.letsblog.ai.controller;

import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.dto.GenerationJobDetailResponse;
import com.letsblog.ai.dto.GenerationJobResponse;
import com.letsblog.ai.repository.GenerationJobRepository;
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
 */
@RestController
@RequestMapping("/api/generation-jobs")
public class GenerationJobController {

    private final GenerationJobRepository generationJobRepository;

    public GenerationJobController(GenerationJobRepository generationJobRepository) {
        this.generationJobRepository = generationJobRepository;
    }

    /**
     * 認可不要: ログイン後の共通ダッシュボード(apps/web/src/app/page.tsx)が表示するジョブ履歴で、
     * 認証済みユーザー全員に見せる前提の画面(issue #830)。
     *
     * <p>ただし generation_jobs には所有者を表す列(userId/projectId)が無く、
     * 「自分のジョブだけ」に絞ることが今のスキーマではできない。利用者ごとに絞るなら
     * 列の追加を伴うため、本Issueのスコープでは現状維持とし、ギャップとして記録するに留める。
     */
    @GetMapping
    public List<GenerationJobResponse> list() {
        // generation_jobs.created_atは秒精度(V1__create_ai_tables.sqlのDATETIME列)のため、
        // createdAtだけでは同一秒に作られた複数ジョブが同値になり、安定ソートの結果
        // findAll()のDB取得順(通常ID昇順)がそのまま残って「新しい順」の契約が崩れる
        // (issue #1332、#934/#1147のシナリオが同一秒の別ジョブを誤って拾った実例)。
        // IDはAUTO_INCREMENTで単調増加するため、第2キーの降順タイブレークに使うことで、
        // createdAtの精度に関わらず作成順を一意に決定できる。
        return generationJobRepository.findAll().stream()
                .sorted(Comparator.comparing(GenerationJob::getCreatedAt)
                        .thenComparing(GenerationJob::getId)
                        .reversed())
                .map(job -> new GenerationJobResponse(
                        job.getId(), job.getType(), job.getStatus(), job.getCreatedAt(), job.getUpdatedAt()))
                .toList();
    }

    /**
     * モデルインストール等の非同期ジョブの完了をフロントがポーリングするためのエンドポイント。
     */
    /** 認可不要: {@link #list}と同じ理由(共通ダッシュボード、所有者列が無い、issue #830)。 */
    @GetMapping("/{id}")
    public GenerationJobDetailResponse get(@PathVariable Long id) {
        GenerationJob job = generationJobRepository.findById(id)
                .orElseThrow(() -> new GenerationJobNotFoundException("id " + id + " のジョブは見つかりません"));
        return new GenerationJobDetailResponse(
                job.getId(), job.getType(), job.getStatus(),
                job.getRequestPayload(), job.getResultPayload(),
                job.getCreatedAt(), job.getUpdatedAt());
    }
}
