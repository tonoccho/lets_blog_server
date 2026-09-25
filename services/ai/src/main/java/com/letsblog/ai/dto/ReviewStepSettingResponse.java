package com.letsblog.ai.dto;

/**
 * 1レビューステップぶんの選択中provider/model。未設定(=プロジェクト既定を使用)はnull
 * (issue #1211。LlmProviderListResponseと同じ契約)。
 */
public record ReviewStepSettingResponse(String stepKey, String provider, String model) {
}
