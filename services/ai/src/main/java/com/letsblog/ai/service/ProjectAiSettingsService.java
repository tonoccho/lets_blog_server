package com.letsblog.ai.service;

import com.letsblog.ai.domain.ProjectAiSettings;
import com.letsblog.ai.repository.ProjectAiSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * プロジェクト単位のAI(LLM)関連設定(project_ai_settings)の読み書きを扱う(issue #571)。
 * projects god-tableの分割で切り出された設定テーブルで、行は初回書き込み時に遅延作成する
 * (未設定のプロジェクトに空行を作らないため)。
 */
@Service
public class ProjectAiSettingsService {

    /** platform-serviceのAppSettingServiceと同じ規約(空白・制御文字を含まない)。 */
    private static final Pattern WHITESPACE_OR_CONTROL = Pattern.compile(".*[\\s\\p{Cntrl}].*", Pattern.DOTALL);

    private final ProjectAiSettingsRepository repository;

    public ProjectAiSettingsService(ProjectAiSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<ProjectAiSettings> findByProjectId(Long projectId) {
        return repository.findByProjectId(projectId);
    }

    @Transactional
    public ProjectAiSettings getOrCreate(Long projectId) {
        return repository.findByProjectId(projectId)
                .orElseGet(() -> repository.save(new ProjectAiSettings(projectId)));
    }

    @Transactional(readOnly = true)
    public String getLlmModel(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::getLlmModel).orElse(null);
    }

    @Transactional
    public void setLlmModel(Long projectId, String llmModel) {
        ProjectAiSettings settings = getOrCreate(projectId);
        settings.setLlmModel(llmModel);
        repository.save(settings);
    }

    @Transactional(readOnly = true)
    public String getLlmProvider(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::getLlmProvider).orElse(null);
    }

    @Transactional
    public void setLlmProvider(Long projectId, String llmProvider) {
        ProjectAiSettings settings = getOrCreate(projectId);
        settings.setLlmProvider(llmProvider);
        repository.save(settings);
    }

    @Transactional(readOnly = true)
    public boolean hasBraveSearchApiKey(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::hasBraveSearchApiKey).orElse(false);
    }

    @Transactional(readOnly = true)
    public byte[] getBraveSearchApiKeyEncrypted(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::getBraveSearchApiKeyEncrypted).orElse(null);
    }

    @Transactional
    public void setBraveSearchApiKeyEncrypted(Long projectId, byte[] braveSearchApiKeyEncrypted) {
        ProjectAiSettings settings = getOrCreate(projectId);
        settings.setBraveSearchApiKeyEncrypted(braveSearchApiKeyEncrypted);
        repository.save(settings);
    }

    @Transactional(readOnly = true)
    public boolean hasOpenAiApiKey(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::hasOpenAiApiKey).orElse(false);
    }

    @Transactional(readOnly = true)
    public byte[] getOpenAiApiKeyEncrypted(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::getOpenAiApiKeyEncrypted).orElse(null);
    }

    /** プロジェクト単位のChatGPT(OpenAI)APIキー(暗号化済み)を保存する。nullで削除(issue #1506)。 */
    @Transactional
    public void setOpenAiApiKeyEncrypted(Long projectId, byte[] openAiApiKeyEncrypted) {
        ProjectAiSettings settings = getOrCreate(projectId);
        settings.setOpenAiApiKeyEncrypted(openAiApiKeyEncrypted);
        repository.save(settings);
    }

    @Transactional(readOnly = true)
    public String getOllamaBaseUrl(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::getOllamaBaseUrl).orElse(null);
    }

    @Transactional(readOnly = true)
    public String getComfyuiBaseUrl(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::getComfyuiBaseUrl).orElse(null);
    }

    /**
     * Ollama / ComfyUI接続先の上書きを保存する(issue #1503)。引数がnullの項目は変更せず、
     * 空文字(空白のみ含む)の項目は上書きを解除する。値はhttp://またはhttps://で始まり、
     * 空白・制御文字を含まないこと。いずれかが不正なら何も保存せず{@link InvalidConnectionUrlException}を投げる。
     */
    @Transactional
    public void setConnectionUrls(Long projectId, String ollamaBaseUrl, String comfyuiBaseUrl) {
        String ollama = normalizeUrl("ollamaBaseUrl", ollamaBaseUrl);
        String comfyui = normalizeUrl("comfyuiBaseUrl", comfyuiBaseUrl);
        ProjectAiSettings settings = getOrCreate(projectId);
        if (ollamaBaseUrl != null) {
            settings.setOllamaBaseUrl(ollama);
        }
        if (comfyuiBaseUrl != null) {
            settings.setComfyuiBaseUrl(comfyui);
        }
        repository.save(settings);
    }

    /** null(変更しない)とnull(解除)を混同しないよう、呼び出し側は元の引数のnullで区別する。 */
    private static String normalizeUrl(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            throw new InvalidConnectionUrlException(field + " はhttp://またはhttps://から始まるURLを指定してください");
        }
        if (WHITESPACE_OR_CONTROL.matcher(value).matches()) {
            throw new InvalidConnectionUrlException(field + " に空白文字・制御文字は使用できません");
        }
        return value;
    }
}
