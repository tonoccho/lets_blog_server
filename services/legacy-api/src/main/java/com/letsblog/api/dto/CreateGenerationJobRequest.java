package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * ジョブを作成するリクエスト(#573 stage3)。media-service側のオーケストレーション
 * (MediaGarbageCollectionService等、legacy-apiに残らないコントローラから起動される非同期処理)が、
 * 引き続きlegacy-apiが所有するGenerationJobをここ経由で作成する。statusは常に"running"で作成する
 * (既存のComfyUiModelService/MediaGarbageCollectionServiceの挙動を踏襲)。
 */
public record CreateGenerationJobRequest(@NotBlank String type, String requestPayload) {
}
