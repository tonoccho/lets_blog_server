package com.letsblog.common.auth;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;

/**
 * lets_blogからextensionsのツール(Ollama・ComfyUI・PlantUML・ポータル)を呼ぶときに付ける、専用クライアント
 * {@code letsblog-tools-client}(ロールを持たない。audienceは{@code lbx-tools})のトークンの供給元(#1804)。
 *
 * <ul>
 *   <li><b>無効</b>(シークレット未設定): {@link #authorization()}は空で、ヘッダーを付けない。従来と同じ。</li>
 *   <li><b>有効</b>: client credentialsのトークンを返す({@link ServiceTokenClient}がキャッシュ・期限前更新する)。</li>
 *   <li><b>有効なのに取得できない</b>: {@link ToolsTokenUnavailableException}。ヘッダー無しで続けない。</li>
 * </ul>
 *
 * <p>トークンを付けてよい宛先(システム設定と{@code PLANTUML_BASE_URL}だけ。プロジェクト単位の上書き先には付けない)の
 * 判断は呼び出し側の責務で、このクラスは宛先を知らない。{@link ServiceTokenClient}をBeanにしないのは、
 * {@code letsblog-services}のBeanと型が衝突しないようにするため。
 */
public final class ToolsTokenProvider {

    private static final ToolsTokenProvider DISABLED = new ToolsTokenProvider(null);

    private final Supplier<String> tokenSupplier;

    private ToolsTokenProvider(Supplier<String> tokenSupplier) {
        this.tokenSupplier = tokenSupplier;
    }

    /** シークレット未設定。トークンを付けない。 */
    public static ToolsTokenProvider disabled() {
        return DISABLED;
    }

    /** {@code client}がnullなら無効。 */
    public static ToolsTokenProvider of(ServiceTokenClient client) {
        return client == null ? DISABLED : new ToolsTokenProvider(client::getAccessToken);
    }

    /** トークンの供給元から作る(呼び出し側のテスト用)。 */
    public static ToolsTokenProvider fromSupplier(Supplier<String> tokenSupplier) {
        return new ToolsTokenProvider(tokenSupplier);
    }

    public boolean enabled() {
        return tokenSupplier != null;
    }

    /**
     * {@code "Bearer <トークン>"}。無効なら空。
     *
     * @throws ToolsTokenUnavailableException 有効なのにトークンを取得できなかった場合
     */
    public Optional<String> authorization() {
        if (tokenSupplier == null) {
            return Optional.empty();
        }
        try {
            return Optional.of("Bearer " + tokenSupplier.get());
        } catch (RuntimeException e) {
            throw new ToolsTokenUnavailableException(
                    "extensionsのツール用トークン(KEYCLOAK_TOOLS_CLIENT_SECRET、クライアント letsblog-tools-client)を"
                            + "取得できませんでした: " + e.getMessage(), e);
        }
    }

    /** 有効ならAuthorizationを付ける。無効なら何もしない。 */
    public void apply(HttpHeaders headers) {
        authorization().ifPresent(value -> headers.set(HttpHeaders.AUTHORIZATION, value));
    }

    /**
     * リクエストごとにトークンを付けるインターセプター(長い呼び出しの途中でトークンが失効しないよう、
     * 都度{@link #authorization()}を引く)。取得に失敗したらリクエストを送らずに例外にする。
     */
    public ClientHttpRequestInterceptor interceptor() {
        return (request, body, execution) -> {
            apply(request.getHeaders());
            return execution.execute(request, body);
        };
    }
}
