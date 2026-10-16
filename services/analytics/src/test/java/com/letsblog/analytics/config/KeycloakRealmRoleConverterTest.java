package com.letsblog.analytics.config;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KeycloakRealmRoleConverter(issue #1827): realm_access.roles を ROLE_ 付きの大文字authorityへ変換する。 */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private static Jwt jwtWithClaim(String name, Object value) {
        return Jwt.withTokenValue("t").header("alg", "RS256").claim("azp", "letsblog-web").claim(name, value)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    @Test
    void realmロールをROLE_付きの大文字へ変換する() {
        Collection<GrantedAuthority> authorities = converter.convert(JwtTestFixtures.jwt("sub", "admin", "editor"));

        assertEquals(List.of("ROLE_ADMIN", "ROLE_EDITOR"),
                authorities.stream().map(GrantedAuthority::getAuthority).toList());
    }

    @Test
    void realm_accessクレームが無ければ空() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").claim("azp", "letsblog-web")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();

        assertTrue(converter.convert(jwt).isEmpty());
    }

    @Test
    void rolesが無ければ空() {
        assertTrue(converter.convert(jwtWithClaim("realm_access", Map.of("other", "x"))).isEmpty());
    }

    @Test
    void rolesが配列でなければ空() {
        assertTrue(converter.convert(jwtWithClaim("realm_access", Map.of("roles", "admin"))).isEmpty());
    }
}
