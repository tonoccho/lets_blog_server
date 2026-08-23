package com.letsblog.identity.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * KeycloakのJWT検証設定(#563)。gatewayのJwtDecoderConfig
 * (services/gateway/src/main/java/com/letsblog/gateway/config/JwtDecoderConfig.java)と
 * 同じ理由により、issuer-uriによる自動設定は使わずJWKS取得先と期待issuerを個別に指定して
 * 組み立てている。gatewayをバイパスして本サービスへ直接アクセスした場合でも、gatewayを
 * 経由した場合と同一の検証(署名・有効期限・issuer)が独立して行われる。
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
