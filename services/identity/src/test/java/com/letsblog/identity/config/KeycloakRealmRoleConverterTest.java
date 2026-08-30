package com.letsblog.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void realm_accessのrolesをROLE_プレフィックス付き大文字authorityへ変換する() {
        Jwt jwt = JwtTestFixtures.jwt("sub-1", "admin", "editor");

        List<String> authorities = converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(authorities).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_EDITOR");
    }

    @Test
    void realm_accessクレームが無い場合は空のauthorityを返す() {
        // JwtTestFixtures.jwt()は常にrealm_accessクレームを付与するため、クレーム自体が
        // 欠落しているケースはここで個別に組み立てる。
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("sub-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThat(converter.convert(jwt)).isEmpty();
    }
}
