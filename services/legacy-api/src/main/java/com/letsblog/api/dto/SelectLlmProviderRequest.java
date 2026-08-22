package com.letsblog.api.dto;

/** providerが空/null時はプロジェクト単位の上書きを解除し、システム設定の既定プロバイダーへ戻す。 */
public record SelectLlmProviderRequest(String provider) {
}
