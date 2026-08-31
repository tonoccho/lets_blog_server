package com.letsblog.identity.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Let's BlogユーザーとWordPressサイト上の著者(ユーザー)IDの対応関係。
 * ProjectUserSyncServiceがプロジェクトメンバー追加/ロール変更時にprovisionAuthorの結果を書き込み、
 * PostPublishService.resolveAuthorIdが投稿時の著者ID解決に使う。
 */
@Entity
@Table(name = "user_site_authors")
@IdClass(UserSiteAuthorId.class)
@Getter
@Setter
@NoArgsConstructor
public class UserSiteAuthor {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "cms_author_id", nullable = false, length = 255)
    private String cmsAuthorId;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public UserSiteAuthor(Long userId, Long siteId, String cmsAuthorId) {
        this.userId = userId;
        this.siteId = siteId;
        this.cmsAuthorId = cmsAuthorId;
    }

    @PrePersist
    @PreUpdate
    void onSave() {
        this.updatedAt = LocalDateTime.now();
    }
}
