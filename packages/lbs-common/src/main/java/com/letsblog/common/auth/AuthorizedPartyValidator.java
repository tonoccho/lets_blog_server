package com.letsblog.common.auth;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * JWTの{@code azp}(authorized party = トークンを要求したクライアント)を許可リストで検査する(#1806)。
 *
 * <p>同じrealmが発行したトークンでも、用途の違うクライアント(extensionsへ送る{@code letsblog-tools-client}、
 * {@code lbx-*}、Keycloak組み込みのクライアントなど)のものは、lets_blogのAPIで受け付けない。
 * 許可するのはlets_blog自身の4クライアントだけで、{@code azp}の無いトークンも拒否する。
 * 拒否は検証の失敗なので、servletのリソースサーバーでもリアクティブ(gateway)でも401になる。
 *
 * <p>{@link OAuth2TokenValidator}なので、{@code NimbusJwtDecoder}にも{@code NimbusReactiveJwtDecoder}にも
 * 同じ形で使える。
 */
public final class AuthorizedPartyValidator implements OAuth2TokenValidator<Jwt> {

    /** 許可するクライアントID(realm-export.json の letsblog-web / -services / -vscode / -e2e)。 */
    public static final Set<String> DEFAULT_ALLOWED = Set.of(
            "letsblog-web", "letsblog-services", "letsblog-vscode", "letsblog-e2e");

    private static final OAuth2Error REJECTED = new OAuth2Error(
            "invalid_token", "The azp claim is missing or not an allowed client", null);

    private final Set<String> allowed;

    public AuthorizedPartyValidator() {
        this(DEFAULT_ALLOWED);
    }

    public AuthorizedPartyValidator(Collection<String> allowed) {
        this.allowed = Set.copyOf(allowed);
    }

    /** 標準の検査(署名以外: 有効期限・issuer)に、{@code azp}の許可リストの検査を重ねたバリデーター。 */
    public static OAuth2TokenValidator<Jwt> withIssuer(String issuer) {
        return new DelegatingOAuth2TokenValidator<>(
                List.of(JwtValidators.createDefaultWithIssuer(issuer), new AuthorizedPartyValidator()));
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object azp = jwt.getClaims().get("azp");
        if (azp instanceof String clientId && allowed.contains(clientId)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(REJECTED);
    }
}
