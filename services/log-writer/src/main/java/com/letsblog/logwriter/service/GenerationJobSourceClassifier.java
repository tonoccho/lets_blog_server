package com.letsblog.logwriter.service;

import java.util.Locale;
import java.util.Map;

/**
 * {@code generation_jobs.type}(ジョブ種別)から、統合操作ログの表示用ソース種別を決める
 * 対応表(issue #1481)。<b>種別と分類の対応はここ1箇所だけ</b>に置く。新しいジョブ種別
 * (#1479 のサイト構築など)が増えたら{@link #CLASSIFICATION}に1行足す。
 *
 * <h2>2つの「ソース種別」の意味</h2>
 * <ul>
 *   <li><b>取得元</b>: ジョブはすべてai-serviceの{@code generation_jobs}に由来し、1回のHTTP呼び出しで
 *       取得する。この取得元を指すのが{@value #AI_JOB}(従来からの値)。</li>
 *   <li><b>表示分類</b>: 取得したジョブをAI処理({@value #AI_JOB})と非AI処理({@value #SYSTEM_JOB})に
 *       分けた結果。画面のバッジと絞り込みはこちらを使う。</li>
 * </ul>
 *
 * <h2>互換方針(明示的な変更)</h2>
 * {@code sourceType=AI_JOB}の絞り込みは、従来は{@code generation_jobs}の全件だったが、今後は
 * <b>AIに分類されたジョブだけ</b>を返す。非AIのジョブは{@code SYSTEM_JOB}で単独に一覧できる。
 * 絞り込みなしは両方を返す。
 */
public final class GenerationJobSourceClassifier {

    /** AI処理のジョブ。また、ジョブ取得元(ai-service)を指す従来からの値。 */
    public static final String AI_JOB = "AI_JOB";
    /** AIではない処理のジョブ(不要メディアの削除など)。 */
    public static final String SYSTEM_JOB = "SYSTEM_JOB";

    /**
     * 既知のジョブ種別の分類。
     *
     * <ul>
     *   <li>{@code image_generation}: 画像生成そのもの。AI。</li>
     *   <li>{@code comfyui_checkpoint_download}: 生成ではないが、AI機能(ComfyUI)のための
     *       モデルのダウンロードであり、AI機能の運用操作なのでAIに含める。</li>
     *   <li>{@code media_garbage_collection_delete}: 不要メディアの削除。AIではない。</li>
     *   <li>{@code environment_sync}: プロジェクトの環境間同期(#1697)。AIではない。</li>
     *   <li>{@code site_provisioning}: サイト自動構築(#1479)。AIではない。</li>
     * </ul>
     */
    private static final Map<String, String> CLASSIFICATION = Map.of(
            "image_generation", AI_JOB,
            "comfyui_checkpoint_download", AI_JOB,
            "media_garbage_collection_delete", SYSTEM_JOB,
            "environment_sync", SYSTEM_JOB,
            "site_provisioning", SYSTEM_JOB);

    /**
     * 未知・null の種別の既定。{@code generation_jobs}は元々AI処理のためのテーブルで、
     * 従来は全件が{@code AI_JOB}だった。既定をAIにすると従来の表示を変えず、分類漏れの
     * 新種別が出ても例外にせず一覧に残る(非AI側に落とすとAI絞り込みから黙って消える)。
     */
    private static final String DEFAULT_CLASSIFICATION = AI_JOB;

    private GenerationJobSourceClassifier() {
    }

    /** ジョブ種別を表示用ソース種別へ分類する。未知・nullは{@value #AI_JOB}(例外にしない)。 */
    public static String classify(String jobType) {
        if (jobType == null) {
            return DEFAULT_CLASSIFICATION;
        }
        return CLASSIFICATION.getOrDefault(jobType, DEFAULT_CLASSIFICATION);
    }

    /** 要求されたソース種別が、ai-serviceからのジョブ取得を必要とするか(未指定・AI_JOB・SYSTEM_JOB)。 */
    public static boolean isJobSourceRequest(String requestedType) {
        return requestedType == null || requestedType.isBlank()
                || AI_JOB.equalsIgnoreCase(requestedType)
                || SYSTEM_JOB.equalsIgnoreCase(requestedType);
    }

    /** 要求されたソース種別(未指定なら全件)にこのジョブ種別が当てはまるか。 */
    public static boolean matches(String requestedType, String jobType) {
        if (requestedType == null || requestedType.isBlank()) {
            return true;
        }
        return classify(jobType).equals(requestedType.toUpperCase(Locale.ROOT));
    }
}
