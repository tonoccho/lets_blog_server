package com.letsblog.ai.dto;

import java.util.List;

/** availableProvidersはAiProviderの全値(常に3件)。selectedはnullなら「グローバル既定を使用」を意味する。 */
public record LlmProviderListResponse(List<String> availableProviders, String selected) {
}
