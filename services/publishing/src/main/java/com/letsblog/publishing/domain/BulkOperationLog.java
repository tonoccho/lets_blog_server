package com.letsblog.publishing.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一括管理(カテゴリ/タグ/プラグイン/テーマ/投稿)の1環境に対する1操作の実行結果。
 * 以前はbulk_operation_logsテーブルへ永続化し、履歴一覧・ロールフォワード(replay)に使っていたが、
 * 永続化・履歴閲覧・replayは廃止し、操作ログ(operation_logs、issue #187で統合済み)へ一本化した
 * (issue #184)。呼び出し元(BulkManagementService等)が各操作の直後の結果を組み立てて画面へ返す、
 * 非永続の値オブジェクトとしてのみ使う(legacy-apiの{@code BulkManagementService}等一式と共に
 * publishing-serviceへ移設したもの、issue #708、Epic #551 C6-2)。
 *
 * <p><b>issue #572の所有権決定と、issue #708でのその後の判断</b>: 物理的なbulk_operation_logs
 * テーブル自体(このクラスは現在マッピングしていない)は、ログの所有権をlog-writerへ完全移管する
 * #572のスコープには含めなかった。当時の理由は、他の3ログテーブル(audit_logs/operation_logs/
 * frontend_error_logs)とは異なりbulk_operation_logsがprojectsテーブル(当時はlegacy-api所有)への
 * FK(fk_bulk_operation_logs_project、V16マイグレーション)を持つ一括WordPress同期のワークフロー
 * 状態であり、純粋な追記型ログではない(ADR-0004はクロススキーマFKを禁じるため、log-writerの
 * lbs_logスキーマへ移すとこのFKが成立しなくなる)ためだった。
 *
 * <p>issue #708(本Issue)の実装時点で、以下2点を確認した。
 * <ol>
 *   <li>Project本体の所有権がproject-serviceへ移った(issue #577 stage2)ことに伴い、
 *       {@code V77__drop_project_service_tables.sql}が{@code fk_bulk_operation_logs_project}
 *       FKを既に削除済みであり、#572時点でこのテーブルをlog-writer側へ移せなかった技術的な
 *       制約(クロススキーマFK)は既に解消している。</li>
 *   <li>このクラスはJPAエンティティとしてマッピングされておらず({@code @Entity}/
 *       {@code @Table}なし)、対応する{@code Repository}も存在しない。legacy-apiの全コードを
 *       検索しても、{@code bulk_operation_logs}テーブルへ実際に書き込む・読み込むコードパスは
 *       存在しない(issue #184で既に操作ログはoperation_logsへ一本化済み)。</li>
 * </ol>
 * <p>以上より、bulk_operation_logsの物理テーブル自体がもはや不要と判断し、legacy-api
 * (lets_blogスキーマ)側のテーブルをFlyway migrationで削除した
 * ({@code V81__drop_bulk_operation_logs.sql}参照)。#184で廃止済みの履歴閲覧・replay機能を
 * 復活させる意図はないため、publishing-service側(lbs_publishingスキーマ)への再作成は行わない。
 */
@Getter
@Setter
@NoArgsConstructor
public class BulkOperationLog {

    private Long projectId;
    private BulkOperationType operationType;
    private BulkOperationSourceType sourceType = BulkOperationSourceType.SLUG;
    private String value;

    // 以下4項目はCATEGORY_CREATE/EDIT/DELETEのみ使用。親カテゴリ・編集/削除対象は
    // term_idではなくスラッグで識別する(term_idは環境ごとに異なるため)。
    private String categorySlug;
    private String categoryParentSlug;
    private String categoryTargetSlug;
    private String categoryDescription;

    private String originalFilename;
    private String storagePath;
    private String fileSha256;

    // POST_STATUS_UPDATEのみ使用。変更後のステータス(publish/draft等)を記録する。
    private String postStatus;

    private String environment;
    private BulkOperationStatus status;
    private BulkOperationLogLevel level;
    private String errorMessage;
    private String stackTrace;
    private Long actorId;
    private LocalDateTime createdAt;
}
