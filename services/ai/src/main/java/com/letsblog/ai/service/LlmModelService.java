package com.letsblog.ai.service;

import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.ai.LlmConfigProvider;
import com.letsblog.ai.dto.LlmModelListResponse;
import com.letsblog.ai.dto.LlmProviderListResponse;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * プロジェクトごとのLLM利用モデル/AIプロバイダーの一覧・選択を扱う(issue #376/#530)。
 * 外部ホスト型LLM APIにはOllamaのようなローカルインストール/pullの概念がないため、
 * モデルについては「どのモデル名を使うか」の選択のみを扱う。
 * データはprojects god-tableの分割(issue #571)によりproject_ai_settings(ProjectAiSettingsService)が保持する。
 *
 * <p>issue #574でai-serviceへ移設。legacy-api版はprojectId存在チェック(ProjectRepository、
 * project-serviceがまだ未抽出のためlegacy-apiに残る)を行っていたが、ADR-0004の
 * 「project_idは外部キーではなく参照キー」という方針、および呼び出し元(ProjectLlmModelController/
 * ArticlePlanController)が既にrequireAdmin/requireProjectMemberOrAdmin経由でプロジェクトの存在を
 * 実質的に検証していることを踏まえ、追加のクロスサービス呼び出しは行わない
 * (存在しないprojectIdに対しては、設定の読み取りは空、書き込みは孤立行の作成に留まる)。
 */
@Service
public class LlmModelService {

    private final ProjectAiSettingsService projectAiSettingsService;
    private final LlmConfigProvider llmConfigProvider;

    public LlmModelService(ProjectAiSettingsService projectAiSettingsService, LlmConfigProvider llmConfigProvider) {
        this.projectAiSettingsService = projectAiSettingsService;
        this.llmConfigProvider = llmConfigProvider;
    }

    public LlmModelListResponse listModelsForProject(Long projectId) {
        return new LlmModelListResponse(llmConfigProvider.availableModels(), getSelectedModel(projectId));
    }

    /**
     * プロジェクトの選択中モデルを返す。未選択(null)ならグローバルデフォルトにフォールバックする。
     */
    public String getSelectedModel(Long projectId) {
        String selected = projectAiSettingsService.getLlmModel(projectId);
        return selected == null || selected.isBlank() ? llmConfigProvider.defaultModel() : selected;
    }

    public LlmModelListResponse selectModel(Long projectId, String modelName) {
        projectAiSettingsService.setLlmModel(projectId, modelName);
        return listModelsForProject(projectId);
    }

    /**
     * プロジェクトのAIプロバイダー設定一覧を返す。selectedは未上書き時null(「グローバル既定を使用」)を
     * そのまま返す(getSelectedProviderのように解決済み値へフォールバックしない)。
     * Web/拡張のUIが「グローバル既定を使用」の空選択肢を表現できるようにするため。
     */
    public LlmProviderListResponse listProvidersForProject(Long projectId) {
        List<String> availableProviders = Arrays.stream(AiProvider.values()).map(Enum::name).toList();
        return new LlmProviderListResponse(availableProviders, projectAiSettingsService.getLlmProvider(projectId));
    }

    /**
     * プロジェクトの選択中AIプロバイダーを返す。未選択(null/空)ならシステム設定の既定プロバイダーに
     * フォールバックする(LlmClient呼び出し時に実際に使うプロバイダーを解決するため)。
     */
    public AiProvider getSelectedProvider(Long projectId) {
        AiProvider override = AiProvider.fromString(projectAiSettingsService.getLlmProvider(projectId));
        return override != null ? override : llmConfigProvider.provider();
    }

    /** providerが空/nullの場合はプロジェクト単位の上書きを解除する(グローバル既定へ戻す)。 */
    public LlmProviderListResponse selectProvider(Long projectId, String provider) {
        AiProvider parsed = AiProvider.fromString(provider);
        projectAiSettingsService.setLlmProvider(projectId, parsed != null ? parsed.name() : null);
        return listProvidersForProject(projectId);
    }
}
