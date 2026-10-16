package com.letsblog.media.testsupport;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.media.client.AiServiceConnectionClient;
import com.letsblog.media.client.PlatformServiceClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

/**
 * 実物の{@link PlatformServiceClient}のうち、ChatGPT画像APIのベースURLだけをテスト用のHTTPサーバーへ向けるもの(issue #1819)。
 *
 * <p>本番の{@code chatGptBaseUrl()}は定数({@code https://api.openai.com/v1}、e2e-stubsプロファイルでは
 * {@code http://image-stub:8080})で、設定で差し替える口が無い。プロダクションコードは変えないので、
 * 上書きするのはその1メソッド(接続先のURLを返すだけ)に限り、platform-service・ai-serviceへのHTTP呼び出し
 * (ComfyUIの接続先・APIキーの解決)は実物がそのまま行う。
 */
public final class ChatGptEndpointPlatformServiceClient extends PlatformServiceClient {

    private volatile String chatGptBaseUrl;

    public ChatGptEndpointPlatformServiceClient(
            String platformServiceUri, ServiceTokenClient serviceTokenClient,
            AiServiceConnectionClient aiServiceConnectionClient, String chatGptBaseUrl) {
        super(RestClient.builder(), platformServiceUri, serviceTokenClient, aiServiceConnectionClient,
                new MockEnvironment());
        this.chatGptBaseUrl = chatGptBaseUrl;
    }

    /** ChatGPT画像APIの接続先を差し替える(到達できない接続先の再現用)。 */
    public void chatGptBaseUrl(String baseUrl) {
        this.chatGptBaseUrl = baseUrl;
    }

    @Override
    public String chatGptBaseUrl() {
        return chatGptBaseUrl;
    }
}
