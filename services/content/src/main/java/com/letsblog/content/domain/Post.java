package com.letsblog.content.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "posts")
@Getter
@Setter
@NoArgsConstructor
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "wp_post_id", length = 255)
    private String wpPostId;

    @Column(length = 255)
    private String slug;

    @Column(name = "local_file_hash", length = 64)
    private String localFileHash;

    /**
     * 画像参照文字列(例: "assets/eyecatch.png")→アップロード済み情報(sha256/url/mediaId)のJSONマップ。
     * 再投稿(更新)時に同一内容の画像を毎回アップロードし直さないよう、PostPublishServiceが参照・更新する。
     */
    @Column(name = "uploaded_images_json", columnDefinition = "TEXT")
    private String uploadedImagesJson;

    /** WordPressへ送信したカテゴリ名のJSON配列(例: ["技術","お知らせ"])。 */
    @Column(columnDefinition = "TEXT")
    private String categories;

    /** WordPressへ送信した予約投稿の公開予定日時。予約指定がない場合はnull。 */
    @Column(name = "publish_scheduled_at")
    private LocalDateTime publishScheduledAt;

    @Column(nullable = false, length = 20)
    private String status = "draft";

    @Column(name = "last_published_at")
    private LocalDateTime lastPublishedAt;

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
