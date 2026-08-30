package com.letsblog.api.dto;

import java.util.List;

/** availableProvidersはImageProviderの全値(常に2件)。selectedはnullなら「ComfyUIを使用」を意味する。 */
public record ImageProviderListResponse(List<String> availableProviders, String selected) {
}
