package com.letsblog.content.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@EntityListeners(com.letsblog.content.service.CustomTagChangeListener.class)
@Table(name = "custom_tags")
@Getter
@Setter
@NoArgsConstructor
public class CustomTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tag_name", nullable = false, unique = true, length = 100)
    private String tagName;

    @Column(name = "html_template", nullable = false, columnDefinition = "TEXT")
    private String htmlTemplate;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "css_content", columnDefinition = "TEXT")
    private String cssContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "tag_format", nullable = false, length = 20)
    private CustomTagFormat tagFormat = CustomTagFormat.BLOCK;

    @Column(name = "project_id")
    private Long projectId;

    /** AI生成時にLLMへのリクエストを元にPenpotへ作成したデザインファイルのURL(ベストエフォート、失敗時はnull)。 */
    @Column(name = "penpot_file_url", length = 500)
    private String penpotFileUrl;

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
