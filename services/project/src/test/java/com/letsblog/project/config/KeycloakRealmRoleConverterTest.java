package com.letsblog.project.config;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keycloakのrealm_accessのロールを、Spring SecurityのROLE_権限へ変換する(issue #1818)。Jwtは実物。 */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("t").header("alg", "none").subject("sub-1").claims(c -> c.putAll(claims)).build();
    }

    @Test
    void realm_accessのロールをROLE_大文字の権限へ変換する() {
        var authorities = converter.convert(jwt(Map.of("realm_access", Map.of("roles", List.of("admin", "user")))));

        assertEquals(List.of("ROLE_ADMIN", "ROLE_USER"), authorities.stream().map(GrantedAuthority::getAuthority).toList());
    }

    @Test
    void realm_accessが無ければ権限なし() {
        assertTrue(converter.convert(jwt(Map.of("scope", "x"))).isEmpty());
    }

    @Test
    void rolesが一覧でなければ権限なし() {
        assertTrue(converter.convert(jwt(Map.of("realm_access", Map.of("roles", "admin")))).isEmpty());
        assertTrue(converter.convert(jwt(Map.of("realm_access", Map.of("other", List.of("admin"))))).isEmpty());
    }
}
