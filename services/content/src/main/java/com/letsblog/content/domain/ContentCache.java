package com.letsblog.content.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * [blogcard]/[amazon] 組み込みタグ向けに、URL単位でスクレイピング結果をキャッシュする。
 * プロジェクトに依存しないグローバルなキャッシュ(同じURLは全プロジェクトで共有)。
 */
@Entity
@Table(name = "content_cache")
@Getter
@Setter
@NoArgsConstructor
public class ContentCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    /** urlのSHA-256ハッシュ(16進64文字)。urlはユニーク制約を張るには長すぎるため、代わりに一意性を保証する。 */
    @Column(name = "url_hash", nullable = false, unique = true, length = 64)
    private String urlHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    private ContentType contentType;

    /** スクレイピング結果(Map<String, String>)をJSONシリアライズしたもの。 */
    @Column(name = "data_json", nullable = false, columnDefinition = "TEXT")
    private String dataJson;

    /** dataJsonのSHA-256ハッシュ。再取得時の変更検知に使う。 */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** 最後にスクレイピングを試行した日時(TTL判定に使う)。 */
    @Column(name = "last_checked_at", nullable = false)
    private LocalDateTime lastCheckedAt;

    /** 最後にdataJsonの内容が実際に変化した日時。 */
    @Column(name = "last_updated_at", nullable = false)
    private LocalDateTime lastUpdatedAt;

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
