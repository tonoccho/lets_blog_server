package com.letsblog.ai.dto;

import java.util.List;
import java.util.Map;

/**
 * 5ステップぶんの選択値と、選択可能なprovider/model一覧(issue #1211)。stepsは
 * ReviewStepKeyの宣言順(=実行順)で並ぶ。
 *
 * <p>availableModelsはシステム既定providerの一覧(provider未設定のステップ用)。
 * availableModelsByProviderはproviderごとの一覧で、ステップ別にproviderを選べるため、
 * 画面は選択中のproviderの一覧だけを候補にする(issue #1423)。
 *
 * <p>issue #1676: 各一覧はプロバイダーへ問い合わせて得たもの。取得に失敗したproviderはシステム設定の
 * 一覧に戻り、そのproviderの名前がfallbackProvidersに載る。defaultProviderはprovider未設定の工程が
 * 使うシステム既定provider(availableModelsの取得元。解決できなければnull)。
 */
public record ReviewStepSettingsResponse(
        List<ReviewStepSettingResponse> steps,
        List<String> availableProviders,
        List<String> availableModels,
        Map<String, List<String>> availableModelsByProvider,
        List<String> fallbackProviders,
        String defaultProvider) {
}
