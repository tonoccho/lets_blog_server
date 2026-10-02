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

import java.time.LocalDateTime;

/**
 * 生成画像を分類する入れ子フォルダ(issue #1493)。横断の共通ツリーで、{@code parentId}がnullなら最上位。
 * 親子はIDだけで持ち(関連マッピングは張らない)、階層の走査はリポジトリの再帰CTEで行う。
 */
@Entity
@Table(name = "generated_image_folders")
@Getter
@Setter
@NoArgsConstructor
public class GeneratedImageFolder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
