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
