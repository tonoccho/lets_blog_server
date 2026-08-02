package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

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

    /**
     * カテゴリ/タグ/プラグイン/テーマの比較テーブル(Phase12)における「正」の環境。
     * test/productionのいずれかのみを許容する(ローカルはマスターにできない)。
     */
    @Column(name = "master_environment", nullable = false, length = 20)
    private String masterEnvironment = "test";

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
