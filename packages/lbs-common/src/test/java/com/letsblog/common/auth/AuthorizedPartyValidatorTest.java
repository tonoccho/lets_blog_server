package com.letsblog.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

@DisplayName("AuthorizedPartyValidator: azpの許可リスト検査(#1806)")
class AuthorizedPartyValidatorTest {

    private static final String ISSUER = "https://example.test/auth/realms/letsblog";

    private static Jwt jwt(String azp, Object azpRaw) {
        Jwt.Builder b = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("s")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (azp != null) {
            b.claim("azp", azp);
        } else if (azpRaw != null) {
            b.claim("azp", azpRaw);
        }
        return b.build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"letsblog-web", "letsblog-services", "letsblog-vscode", "letsblog-e2e"})
    @DisplayName("許可リストの4クライアントのazpは受理する")
    void acceptsAllowedAzp(String azp) {
        OAuth2TokenValidatorResult result = new AuthorizedPartyValidator().validate(jwt(azp, null));
        assertThat(result.hasErrors()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"letsblog-tools-client", "lbx-proxy", "lbx-gitlab", "lbx-penpot", "account",
            "account-console", "admin-cli", "broker", "realm-management", "security-admin-console", "",
            "LETSBLOG-WEB", "letsblog-web "})
    @DisplayName("許可リストに無いazpは invalid_token で拒否する")
    void rejectsOtherAzp(String azp) {
        OAuth2TokenValidatorResult result = new AuthorizedPartyValidator().validate(jwt(azp, null));
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement().satisfies(e -> {
            assertThat(e.getErrorCode()).isEqualTo("invalid_token");
            assertThat(e.getDescription()).contains("azp");
        });
    }

    @Test
    @DisplayName("azpの無いトークンも拒否する")
    void rejectsMissingAzp() {
        OAuth2TokenValidatorResult result = new AuthorizedPartyValidator().validate(jwt(null, null));
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement()
                .satisfies(e -> assertThat(e.getErrorCode()).isEqualTo("invalid_token"));
    }

    @Test
    @DisplayName("azpが文字列でないトークンも拒否する")
    void rejectsNonStringAzp() {
        OAuth2TokenValidatorResult result =
                new AuthorizedPartyValidator().validate(jwt(null, List.of("letsblog-web")));
        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    @DisplayName("許可リストを指定して作れる")
    void customAllowList() {
        AuthorizedPartyValidator v = new AuthorizedPartyValidator(List.of("only-this"));
        assertThat(v.validate(jwt("only-this", null)).hasErrors()).isFalse();
        assertThat(v.validate(jwt("letsblog-web", null)).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("withIssuer: 標準の検査(issuer)とazpの検査の両方を課す")
    void withIssuerCombinesBoth() {
        OAuth2TokenValidator<Jwt> validator = AuthorizedPartyValidator.withIssuer(ISSUER);
        assertThat(validator.validate(jwt("letsblog-web", null)).hasErrors()).isFalse();
        assertThat(validator.validate(jwt("letsblog-tools-client", null)).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(null, null)).hasErrors()).isTrue();

        Jwt wrongIssuer = Jwt.withTokenValue("t").header("alg", "RS256").issuer("https://other.test")
                .claim("azp", "letsblog-web").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();
        assertThat(validator.validate(wrongIssuer).hasErrors()).isTrue();
    }
}
