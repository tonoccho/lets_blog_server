package com.letsblog.api.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一括管理(カテゴリ/タグ/プラグイン/テーマ/投稿)の1環境に対する1操作の実行結果。
 * 以前はbulk_operation_logsテーブルへ永続化し、履歴一覧・ロールフォワード(replay)に使っていたが、
 * 永続化・履歴閲覧・replayは廃止し、操作ログ(operation_logs、issue #187で統合済み)へ一本化した
 * (issue #184)。呼び出し元(BulkManagementService等)が各操作の直後の結果を組み立てて画面へ返す、
 * 非永続の値オブジェクトとしてのみ使う。
 *
 * <p><b>issue #572の所有権決定</b>: 物理的なbulk_operation_logsテーブル自体(このクラスは
 * 現在マッピングしていない)は、ログの所有権をlog-writerへ完全移管する#572のスコープには
 * 含めない。他の3ログテーブル(audit_logs/operation_logs/frontend_error_logs)とは異なり、
 * bulk_operation_logsはprojectsテーブル(legacy-api所有)へのFK(fk_bulk_operation_logs_project、
 * V16マイグレーション)を持つ一括WordPress同期のワークフロー状態であり、純粋な追記型ログでは
 * ない(ADR-0004はクロススキーマFKを禁じるため、log-writerのlbs_logスキーマへ移すとこのFKが
 * 成立しなくなる)。既にgateway(services/gateway/src/main/resources/application.yml)が
 * /api/bulk-management/**をpublishing想定のルートとして扱っていることも、この領域が
 * 将来のpublishing-service(C6/#575、未着手)に属する想定であることを裏付ける。そのため
 * bulk_operation_logsテーブル自体は当面legacy-api(lets_blogスキーマ)に残す
 * (詳細は#572のPR説明を参照)。
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
