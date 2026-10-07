package com.letsblog.ai.service;

import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.ai.LlmConfigProvider;
import com.letsblog.ai.dto.LlmModelListResponse;
import com.letsblog.ai.dto.LlmProviderListResponse;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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
    private final ProviderModelCatalog providerModelCatalog;

    public LlmModelService(
            ProjectAiSettingsService projectAiSettingsService, LlmConfigProvider llmConfigProvider,
            ProviderModelCatalog providerModelCatalog) {
        this.projectAiSettingsService = projectAiSettingsService;
        this.llmConfigProvider = llmConfigProvider;
        this.providerModelCatalog = providerModelCatalog;
    }

    /**
     * 選択肢は、プロジェクトの実効プロバイダーへ実際に問い合わせて得たモデルの一覧(issue #1674)。
     * 取得に失敗したら、システム設定のリスト({@code availableModelsFor})に戻し、{@code fallback}で知らせる。
     */
    public LlmModelListResponse listModelsForProject(Long projectId) {
        AiProvider provider = getSelectedProvider(projectId);
        llmConfigProvider.useProject(projectId);
        Optional<List<String>> fetched = providerModelCatalog.fetch(provider);
        String selected = getSelectedModel(projectId, provider);
        if (fetched.isPresent()) {
            return new LlmModelListResponse(fetched.get(), selected, false);
        }
        return new LlmModelListResponse(llmConfigProvider.availableModelsFor(provider), selected, true);
    }

    /** プロジェクトの選択中プロバイダーで使うモデルを返す(issue #1644)。 */
    public String getSelectedModel(Long projectId) {
        return getSelectedModel(projectId, getSelectedProvider(projectId));
    }

    /**
     * 指定プロバイダーで使うモデルを返す(issue #1644)。そのプロバイダーに対するプロジェクトの指定を優先し、
     * 無ければそのプロバイダーのシステム既定モデルにフォールバックする。providerがnull(プロジェクトの
     * プロバイダー解決が空)のときは、システム既定プロバイダーの既定モデルを使う。
     */
    public String getSelectedModel(Long projectId, AiProvider provider) {
        AiProvider effective = provider != null ? provider : llmConfigProvider.provider();
        String selected = projectAiSettingsService.getLlmModel(projectId, effective);
        return selected == null || selected.isBlank() ? llmConfigProvider.defaultModelFor(effective) : selected;
    }

    /** プロジェクトの選択中プロバイダーのモデルとして保存する(issue #1644)。 */
    public LlmModelListResponse selectModel(Long projectId, String modelName) {
        projectAiSettingsService.setLlmModel(projectId, getSelectedProvider(projectId), modelName);
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
