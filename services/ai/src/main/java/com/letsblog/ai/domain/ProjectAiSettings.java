package com.letsblog.ai.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * プロジェクト単位のAI(LLM)関連設定(issue #571)。projects god-tableの分割で、AIサービスが
 * 概念上所有する設定を切り出したもの。projectIdは外部キーではなく参照キーとして保持する
 * (将来AIサービスが物理分離された際にクロスDB外部キーにならないようにするため)。
 */
@Entity
@Table(name = "project_ai_settings")
@Getter
@Setter
@NoArgsConstructor
public class ProjectAiSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, unique = true)
    private Long projectId;

    @Column(name = "llm_model", length = 255)
    private String llmModel;

    /**
     * プロジェクト単位のAIプロバイダー既定値(OLLAMA/OPENAI/CLAUDE、issue #530)。未設定時はシステム設定の
     * 既定プロバイダーにフォールバックする(LlmModelService#getSelectedProvider)。
     */
    @Column(name = "llm_provider", length = 20)
    private String llmProvider;

    /** プロジェクト単位のOllama接続先の上書き(issue #1503)。null/空はシステム設定へフォールバックする。 */
    @Column(name = "ollama_base_url", length = 500)
    private String ollamaBaseUrl;

    /** プロジェクト単位のComfyUI接続先の上書き(issue #1503)。null/空はシステム設定へフォールバックする。 */
    @Column(name = "comfyui_base_url", length = 500)
    private String comfyuiBaseUrl;

    @Column(name = "brave_search_api_key_encrypted", columnDefinition = "VARBINARY(1024)")
    private byte[] braveSearchApiKeyEncrypted;

    /** プロジェクト単位のChatGPT(OpenAI)APIキー(CredentialCipherで暗号化、issue #1506)。null/空はシステム設定へフォールバックする。 */
    @Column(name = "openai_api_key_encrypted", columnDefinition = "VARBINARY(1024)")
    private byte[] openAiApiKeyEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ProjectAiSettings(Long projectId) {
        this.projectId = projectId;
    }

    public boolean hasBraveSearchApiKey() {
        return braveSearchApiKeyEncrypted != null && braveSearchApiKeyEncrypted.length > 0;
    }

    public boolean hasOpenAiApiKey() {
        return openAiApiKeyEncrypted != null && openAiApiKeyEncrypted.length > 0;
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
