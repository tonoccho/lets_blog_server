package com.letsblog.publishing.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KeycloakのJWTのrealm_access.rolesをSpring SecurityのROLE_*へ変換する(issue #1816)。実物のJwtで検証する。 */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject("s")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        if (claims.isEmpty()) {
            builder.claim("scope", "x");
        }
        return builder.build();
    }

    @Test
    void rolesは大文字のROLE接頭辞つきの権限になる() {
        Collection<GrantedAuthority> authorities =
                converter.convert(jwt(Map.of("realm_access", Map.of("roles", List.of("admin", "user")))));

        assertEquals(List.of("ROLE_ADMIN", "ROLE_USER"), authorities.stream().map(GrantedAuthority::getAuthority).toList());
    }

    @Test
    void realm_accessが無ければ権限なし() {
        assertTrue(converter.convert(jwt(Map.of())).isEmpty());
    }

    @Test
    void realm_accessにrolesが無ければ権限なし() {
        assertTrue(converter.convert(jwt(Map.of("realm_access", Map.of("other", "x")))).isEmpty());
    }

    @Test
    void rolesが配列でなければ権限なし() {
        assertTrue(converter.convert(jwt(Map.of("realm_access", Map.of("roles", "admin")))).isEmpty());
    }
}
