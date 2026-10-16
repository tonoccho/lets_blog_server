package com.letsblog.ai.ai;

import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.service.CurrentActorService;
import com.letsblog.ai.service.ProjectAiSettingsService;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.common.net.ConnectionDestinationGuard;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 実物の{@link RemoteLlmConfigProvider}のうち、コード内で固定されたクラウドLLMのホスト
 * ({@code api.openai.com} / {@code api.anthropic.com})だけを、テストのHTTPフェイクへ向け替える(issue #1823)。
 *
 * <p>本物のAPIには届かせられないので、HTTPの宛先の差し替え(ネットワーク層でのリダイレクトに相当)として扱い、
 * 設定の解決・プロジェクト単位の上書き・APIキーの復号・宛先検査・キャッシュなどの分岐はすべて実物のまま通す。
 * RemoteLlmConfigProviderのテスト用コンストラクタはpackage-privateなので、このクラスは同じパッケージに置く。
 */
public class RedirectedLlmConfigProvider extends RemoteLlmConfigProvider {

    private final String cloudBaseUrl;

    public RedirectedLlmConfigProvider(
            PlatformServiceClient platformServiceClient, CurrentActorService currentActorService,
            HttpServletRequest request, ProjectAiSettingsService projectAiSettingsService,
            CredentialCipher credentialCipher, ConnectionDestinationGuard destinationGuard, String cloudBaseUrl) {
        super(platformServiceClient, currentActorService, request, projectAiSettingsService, credentialCipher,
                destinationGuard);
        this.cloudBaseUrl = cloudBaseUrl;
    }

    @Override
    public String baseUrlFor(AiProvider provider) {
        if (provider == AiProvider.OPENAI || provider == AiProvider.CLAUDE) {
            return cloudBaseUrl;
        }
        return super.baseUrlFor(provider);
    }
}
