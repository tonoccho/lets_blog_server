package com.letsblog.analytics.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * サービスアカウントのJSON鍵から、Google OAuth2のJWT Bearerグラント(RFC 7523)向けの
 * 自己署名JWTを組み立てる。google-api-client等の重量級SDKは使わず、RSA署名は標準のjava.securityのみで行う。
 */
@Component
public class GoogleServiceAccountJwtSigner {

    private static final long EXPIRES_IN_SECONDS = 3600;

    private final ObjectMapper objectMapper;

    public GoogleServiceAccountJwtSigner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String sign(GoogleServiceAccountKey key, String scope, String audience) {
        try {
            long now = Instant.now().getEpochSecond();
            String header = base64Url(objectMapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));

            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", key.clientEmail());
            claims.put("scope", scope);
            claims.put("aud", audience);
            claims.put("iat", now);
            claims.put("exp", now + EXPIRES_IN_SECONDS);
            String claimsSegment = base64Url(objectMapper.writeValueAsBytes(claims));

            String signingInput = header + "." + claimsSegment;
            byte[] signature = signWithRsa(signingInput.getBytes(StandardCharsets.UTF_8), key.privateKey());
            return signingInput + "." + base64Url(signature);
        } catch (Exception e) {
            throw new GoogleAnalyticsException("サービスアカウント認証情報の署名に失敗しました", e);
        }
    }

    private byte[] signWithRsa(byte[] data, String privateKeyPem) throws Exception {
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            throw new GoogleAnalyticsException("サービスアカウントJSONにprivate_keyが含まれていません", null);
        }
        String normalized = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(normalized);
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(data);
        return signature.sign();
    }

    private String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
