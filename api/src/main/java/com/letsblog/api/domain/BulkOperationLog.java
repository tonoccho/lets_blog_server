package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "bulk_operation_logs", indexes = {
        @Index(name = "idx_bulk_operation_logs_project_id", columnList = "project_id"),
        @Index(name = "idx_bulk_operation_logs_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class BulkOperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "operation_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private BulkOperationType operationType;

    @Column(name = "source_type", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private BulkOperationSourceType sourceType = BulkOperationSourceType.SLUG;

    @Column(name = "value", nullable = false)
    private String value;

    // 以下4項目はCATEGORY_CREATE/EDIT/DELETEのみ使用。ロールフォワード時に同じ内容で再現するため保持する。
    // 親カテゴリ・編集/削除対象はterm_idではなくスラッグで識別する(term_idは環境ごとに異なるため)。
    @Column(name = "category_slug", length = 200)
    private String categorySlug;

    @Column(name = "category_parent_slug", length = 200)
    private String categoryParentSlug;

    @Column(name = "category_target_slug", length = 200)
    private String categoryTargetSlug;

    @Column(name = "category_description", columnDefinition = "TEXT")
    private String categoryDescription;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "storage_path", length = 500)
    private String storagePath;

    @Column(name = "file_sha256", length = 64)
    private String fileSha256;

    @Column(name = "environment", nullable = false, length = 20)
    private String environment;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private BulkOperationStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    @Column(name = "is_replay", nullable = false)
    private boolean replay = false;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
