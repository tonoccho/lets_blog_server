package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "project_users")
@IdClass(ProjectUserId.class)
@Getter
@Setter
@NoArgsConstructor
public class ProjectUser {

    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "wp_role", nullable = false, length = 50)
    private String wpRole;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public ProjectUser(Long projectId, Long userId, String wpRole) {
        this.projectId = projectId;
        this.userId = userId;
        this.wpRole = wpRole;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
