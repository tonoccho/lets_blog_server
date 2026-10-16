package com.letsblog.media.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** {@link KeycloakRealmRoleConverter}(issue #1820)。実際の{@link Jwt}を作って、realm_accessの読み方を検証する。 */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject("s");
        claims.forEach(builder::claim);
        return builder.build();
    }

    private List<String> authorities(Map<String, Object> claims) {
        return converter.convert(jwt(claims)).stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void realm_accessが無ければ権限なし() {
        assertThat(authorities(Map.of("foo", "bar"))).isEmpty();
    }

    @Test
    void realm_accessにrolesが無ければ権限なし() {
        assertThat(authorities(Map.of("realm_access", Map.of("other", "x")))).isEmpty();
    }

    @Test
    void rolesが配列でなければ権限なし() {
        assertThat(authorities(Map.of("realm_access", Map.of("roles", "admin")))).isEmpty();
    }

    @Test
    void rolesを大文字化してROLE_を付ける() {
        assertThat(authorities(Map.of("realm_access", Map.of("roles", List.of("admin", "User")))))
                .containsExactly("ROLE_ADMIN", "ROLE_USER");
    }
}
