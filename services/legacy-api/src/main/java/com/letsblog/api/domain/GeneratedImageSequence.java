package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "generated_image_sequences")
@Getter
@Setter
@NoArgsConstructor
public class GeneratedImageSequence {

    @Id
    @Column(name = "project_key", length = 50)
    private String projectKey;

    @Column(name = "last_seq", nullable = false)
    private Integer lastSeq = 0;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public GeneratedImageSequence(String projectKey) {
        this.projectKey = projectKey;
        this.lastSeq = 0;
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    void onCreate() {
        if (this.updatedAt == null) {
            this.updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
