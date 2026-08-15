package com.letsblog.api.analytics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GoogleサービスアカウントのJSON鍵ファイルから読み取る、GA4 Data API呼び出しに必要な項目のみ。
 * ユーザーがGoogle Cloud Consoleでダウンロードするファイルには他にも項目があるため未知のプロパティは無視する。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleServiceAccountKey(
        @JsonProperty("client_email") String clientEmail,
        @JsonProperty("private_key") String privateKey,
        @JsonProperty("token_uri") String tokenUri
) {
}
