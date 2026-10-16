package com.letsblog.platform.config;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link KeycloakRealmRoleConverter}。realm_access.rolesをROLE_大文字のauthorityへ変換し、無い・形が違うときは空にする。 */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject("sub");
        claims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    void ロールをROLE_大文字のauthorityに変換する() {
        var authorities = converter.convert(jwt(Map.of("realm_access", Map.of("roles", List.of("admin", "editor")))));

        assertEquals(List.of("ROLE_ADMIN", "ROLE_EDITOR"), authorities.stream().map(GrantedAuthority::getAuthority).toList());
    }

    @Test
    void realm_accessが無ければ空() {
        assertTrue(converter.convert(jwt(Map.of("scope", "x"))).isEmpty());
    }

    @Test
    void rolesが無い_配列でないときは空() {
        assertTrue(converter.convert(jwt(Map.of("realm_access", Map.of("other", "x")))).isEmpty());
        assertTrue(converter.convert(jwt(Map.of("realm_access", Map.of("roles", "admin")))).isEmpty());
    }
}
