package com.letsblog.media.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "generated_images")
@Getter
@Setter
@NoArgsConstructor
public class GeneratedImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id")
    private Long projectId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "negative_prompt", columnDefinition = "TEXT")
    private String negativePrompt;

    @Column
    private Integer steps;

    @Column(name = "cfg_scale", precision = 5, scale = 2)
    private BigDecimal cfgScale;

    @Column(name = "sampler_name", length = 100)
    private String samplerName;

    @Column(length = 100)
    private String scheduler;

    @Column
    private Long seed;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(name = "batch_size")
    private Integer batchSize;

    @Column(length = 255)
    private String checkpoint;

    @Column(name = "lora_name", length = 255)
    private String loraName;

    @Column(name = "lora_weight", precision = 5, scale = 2)
    private BigDecimal loraWeight;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType = "image/png";

    /** どの画像生成AIで生成したか(COMFYUI/CHATGPT、issue #531)。 */
    @Column(nullable = false, length = 20)
    private String provider = "COMFYUI";

    /**
     * 検索・分類用のタグ(issue #281)。JSON配列文字列として保持し、パース/組み立ては
     * 呼び出し側(GeneratedImageController等)で行う(Post.uploadedImagesJsonと同じ方針)。
     */
    @Column(name = "tags_json", columnDefinition = "TEXT")
    private String tagsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
