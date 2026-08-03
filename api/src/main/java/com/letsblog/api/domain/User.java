package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /**
     * BFF(Next.js/NextAuth)がヘッダ経由で送るactorRole判定に使う最小権限区分("admin"/"user")。
     * 廃止はせず、細粒度なロール・権限管理は roles(Role/Permission) 側で行う。
     */
    @Column(nullable = false, length = 20)
    private String role;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "nickname", length = 100)
    private String nickname;

    @Column(name = "website_url", length = 500)
    private String websiteUrl;

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    @Column(name = "locale", length = 10)
    private String locale;

    // JPAのINSERTは全カラムを明示するため、DBカラムのDEFAULT句は新規作成時に効かない。
    // ここでのフィールド初期値が実質的なデフォルト値になる。
    @Column(name = "timezone", length = 50)
    private String timezone = "Asia/Tokyo";

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "department", length = 100)
    private String department;

    @Column(name = "position", length = 100)
    private String position;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "social_links", columnDefinition = "json")
    private SocialLinks socialLinks;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_links", columnDefinition = "json")
    private List<CustomLink> customLinks;

    @Column(name = "github_token_encrypted", columnDefinition = "VARBINARY(1024)")
    private byte[] githubTokenEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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

    public boolean hasPermission(Permission permission) {
        return roles.stream().anyMatch(r -> r.hasPermission(permission));
    }

    public boolean hasRole(String roleName) {
        return roles.stream().anyMatch(r -> r.getRoleName().equals(roleName));
    }
}
