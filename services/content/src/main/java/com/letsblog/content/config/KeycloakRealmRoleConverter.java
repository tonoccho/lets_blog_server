package com.letsblog.content.config;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * KeycloakのJWTが持つrealmロール({@code realm_access.roles}、例: "admin"/"editor"/"viewer")を
 * Spring Securityのauthority({@code ROLE_ADMIN}等、大文字+ROLE_プレフィックス)へ変換する。
 * identity-service/log-writer/media-service/ai-serviceのKeycloakRealmRoleConverterと同一の実装。
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @SuppressWarnings("unchecked")
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof List<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(String::valueOf)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)))
                .map(GrantedAuthority.class::cast)
                .toList();
    }
}
