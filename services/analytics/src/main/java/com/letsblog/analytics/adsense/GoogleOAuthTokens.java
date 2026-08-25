package com.letsblog.analytics.adsense;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Googleのトークンエンドポイント(認可コード交換/リフレッシュ)レスポンスから取り出す項目。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleOAuthTokens(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken
) {
}
