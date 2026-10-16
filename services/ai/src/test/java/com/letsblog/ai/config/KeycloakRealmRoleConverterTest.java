package com.letsblog.ai.config;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KeycloakRealmRoleConverter(issue #1824): realm_accessの有無・rolesの型ごとに、authorityへ変換する。 */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject("s")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        if (claims.isEmpty()) {
            builder.claim("dummy", "x");
        }
        return builder.build();
    }

    @Test
    void realmロールをROLE_大文字のauthorityにする() {
        Collection<GrantedAuthority> authorities = converter.convert(
                jwt(Map.of("realm_access", Map.of("roles", List.of("admin", "editor")))));

        assertEquals(List.of("ROLE_ADMIN", "ROLE_EDITOR"), authorities.stream().map(GrantedAuthority::getAuthority).toList());
    }

    @Test
    void realm_accessが無ければ空() {
        assertTrue(converter.convert(jwt(Map.of())).isEmpty());
    }

    @Test
    void rolesが無ければ空() {
        assertTrue(converter.convert(jwt(Map.of("realm_access", Map.of("other", "x")))).isEmpty());
    }

    @Test
    void rolesが配列でなければ空() {
        assertTrue(converter.convert(jwt(Map.of("realm_access", Map.of("roles", "admin")))).isEmpty());
    }
}
