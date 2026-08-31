package com.letsblog.api.dto;

/** providerが空/null時はプロジェクト単位の上書きを解除し、ComfyUIへ戻す。 */
public record SelectImageProviderRequest(String provider) {
}
