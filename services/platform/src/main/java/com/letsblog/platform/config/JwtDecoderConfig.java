package com.letsblog.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * KeycloakのJWT検証設定。他サービスのJwtDecoderConfigと同じ理由により、issuer-uriによる自動設定は
 * 使わずJWKS取得先と期待issuerを個別に指定して組み立てている。gatewayをバイパスして本サービスへ
 * 直接アクセスした場合でも、gatewayを経由した場合と同一の検証(署名・有効期限・issuer)が
 * 独立して行われる。
 */
@Configuration
public class JwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${keycloak.jwk-set-uri}") String jwkSetUri, @Value("${keycloak.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(issuer);
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
