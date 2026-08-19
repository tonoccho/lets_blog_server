package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Bufferへ予約投稿を依頼したジョブの記録(issue #379)。1記事の公開につき1行(複数SNSプラットフォームへの
 * 投稿はBuffer側のprofile_ids指定でまとめて扱うため、プラットフォームごとに分けない)。
 */
@Entity
@Table(name = "buffer_posts")
@Getter
@Setter
@NoArgsConstructor
public class BufferPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(nullable = false, length = 20)
    private String status = "pending";

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload")
    private String requestPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_payload")
    private String resultPayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
