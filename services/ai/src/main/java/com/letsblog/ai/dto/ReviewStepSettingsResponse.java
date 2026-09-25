package com.letsblog.ai.dto;

import java.util.List;

/**
 * 5ステップぶんの選択値と、選択可能なprovider/model一覧(issue #1211)。stepsは
 * ReviewStepKeyの宣言順(=実行順)で並ぶ。
 */
public record ReviewStepSettingsResponse(
        List<ReviewStepSettingResponse> steps, List<String> availableProviders, List<String> availableModels) {
}
