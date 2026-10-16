package com.letsblog.content.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Keycloak の realm_access.roles を権限へ変換する。実物のコンバーターへ実物の {@link Jwt} を渡す。 */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private Jwt.Builder jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject("u");
    }

    private List<String> authorities(Jwt jwt) {
        return converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void ロールを大文字にしてROLE_を付ける() {
        Jwt jwt = jwt().claim("realm_access", Map.of("roles", List.of("admin", "User"))).build();

        assertThat(authorities(jwt)).containsExactly("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void realm_accessが無ければ権限なし() {
        assertThat(authorities(jwt().build())).isEmpty();
    }

    @Test
    void realm_accessにrolesが無ければ権限なし() {
        assertThat(authorities(jwt().claim("realm_access", Map.of("other", "x")).build())).isEmpty();
    }

    @Test
    void rolesがリストでなければ権限なし() {
        assertThat(authorities(jwt().claim("realm_access", Map.of("roles", "admin")).build())).isEmpty();
    }
}
