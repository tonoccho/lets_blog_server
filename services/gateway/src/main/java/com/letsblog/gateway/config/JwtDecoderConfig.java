package com.letsblog.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * KeycloakのJWT検証設定(#560)。
 *
 * <p>gatewayはコンテナ間通信でKeycloakへ直接アクセスするため、JWKS取得先(jwk-set-uri)は
 * {@code http://keycloak:8080/...} という内部アドレスになる。一方、実際に発行される
 * トークンの {@code iss} claim は、ブラウザからのアクセス経路に合わせて固定した公開URL
 * ({@code https://localhost/auth/realms/letsblog}。#559でKC_HOSTNAMEとして固定済み)になる。
 *
 * <p>Spring Bootの{@code spring.security.oauth2.resourceserver.jwt.issuer-uri}による
 * 自動設定は、「specifiedしたissuer-uriへ discovery documentを取りに行き、
 * その中のissuerフィールドがissuer-uriと一致すること」を前提にしているため、
 * 内部アドレスと公開issuerが異なるこの構成には使えない。そのため、JWKS取得先と
 * 期待issuerを個別に指定してJwtDecoderを直接組み立てている。
 */
@Configuration
public class JwtDecoderConfig {

    @Bean
    public ReactiveJwtDecoder jwtDecoder(
            @Value("${keycloak.jwk-set-uri}") String jwkSetUri,
            @Value("${keycloak.issuer}") String issuer) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(issuer);
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
