package com.letsblog.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void realm_accessのrolesをROLE_プレフィックス付き大文字authorityへ変換する() {
        Jwt jwt = jwtWithRealmRoles(List.of("admin", "editor"));

        List<String> authorities = converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(authorities).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_EDITOR");
    }

    @Test
    void realm_accessクレームが無い場合は空のauthorityを返す() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("sub-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    private Jwt jwtWithRealmRoles(List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("sub-1")
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
