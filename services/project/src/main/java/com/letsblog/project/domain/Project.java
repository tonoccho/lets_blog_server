package com.letsblog.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * project-serviceが所有する{@code projects}(issue #577 stage2)。C2(#571)でprojects god-tableを
 * サービス別設定テーブルへ分割済みのため、legacy-api版と同じ縮小後のカラム構成
 * (id/name/slug/*_site_id/master_environment/github_repository/github_token_encrypted)を持つ。
 *
 * <p>legacy-apiの{@code com.letsblog.api.domain.Project}は、まだこのstageでは移設しない他の
 * legacy-apiドメイン(bulk-management/comparison/analytics report等)から広く参照されているため、
 * 削除せず据え置く(legacy-api自身の{@code lets_blog}スキーマの既存データをそのまま参照し続ける)。
 * 本エンティティは新規に独立したスキーマ({@code lbs_project})を対象とし、Web/gateway経由の新規
 * プロジェクト作成・更新はこちらが正とする。
 */
@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "local_site_id")
    private Long localSiteId;

    @Column(name = "test_site_id")
    private Long testSiteId;

    @Column(name = "production_site_id")
    private Long productionSiteId;

    @Column(name = "master_environment", nullable = false, length = 20)
    private String masterEnvironment = "test";

    @Column(name = "github_repository", length = 255)
    private String githubRepository;

    @Column(name = "github_token_encrypted", columnDefinition = "VARBINARY(1024)")
    private byte[] githubTokenEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isGithubRepositoryConfigured() {
        return githubRepository != null && !githubRepository.isBlank();
    }

    public boolean hasGithubToken() {
        return githubTokenEncrypted != null && githubTokenEncrypted.length > 0;
    }

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
