package com.letsblog.api.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GoogleServiceAccountJwtSignerの回帰テスト(issue #386)。テスト用に生成した(実際のGoogleアカウントとは
 * 無関係な)RSA鍵ペアで、生成したJWTの中身(claims)と署名の妥当性を検証する。
 */
class GoogleServiceAccountJwtSignerTest {

    private static final String PRIVATE_KEY_PEM = """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC2mlclKKyllzeV
            Cx3Qgwdk7Jrrpk6lA77G6TbnXjmNB9EDbn0IqYa/eqmbixUKywLHPRi2ZZ/4pjcf
            XgCPsJo72/yVmlA0pHSFsxhS61RZqfJRtZx+jW/isty30OimWvwJtCHgMTdsWUFt
            LJ264s30CiQ1ZNVtPCzCoYJ/qNOR+rvoEr/CMCU9ZU8TGQD08csdurBW2+SY7Buw
            o2NnypgGH98gVLdF9BY6xVRGvCvWHfM3kfo8gzQz4amVyKfXw8GllPScn1wcpu9m
            2EU7Eoct34GVITVzD7qvY+jHvtsZgBoxUvL6H3MMmaxtzh0nrA3Xtw7FX32f0ZH3
            snBQch+PAgMBAAECggEAJokKxAJB8Q4pAjCe4Z6NRGy0Qu/NYACa1bJozknxvkP8
            hYtfIqFYGPejbHpc/fKayv4nRXLL4Db/ogR9/NTpr6E8vDudGobsOjzx8KnOGsAF
            Ld40QPbLOl3Bu58AQf8oeknD7mKkjh6F8qq8PLDZgttTCduWON++mHJqLlOsFn2m
            hV3sdIJOvFxFEAEz/+wS1bWmYcCkDYyiSAlvCAAECWBAsxG3QumH0AuvJKTETEw5
            m18W9w9PcdSvIk5y3SLp7zXZPDoDBVnNq/22VM50XP3UhG+uwvnslVQUdMt9Inn9
            6syXxmQqqKEW8jSpoIII5RJ20xqdD5VKPpz7Q1MjgQKBgQDoSo/zXdSQfZN4MboM
            0bQlQFh3Aw8Ygx3gsAkhB6oNZH5kH4cj1D/io5CiF2luu8g+AJLCmNMbJk5aVLQV
            B+s/ikd4qCU/Ld1bLTAvdaUis2DbXh8CVId/8Fe1/buQ+MP6gvpuBASvLgiCV7Ih
            3QX5Lv5puls1pcAqT6VOAkRCgQKBgQDJPX4Gsv3oWIQscjbtFUYXxzftZIORFOUo
            c82+OdRWAC7NMKSpw75GpSTPpXt2G3al2ZMDEZpzV18mSsvmmrCNsQX4+AkbI82D
            DUprXpc7FRENcG582aT5X8sJ4B+0FCVV5BIBPuxZz9s9Qc8YTXWRemATv70LB5U5
            ynmyROE6DwKBgQDn6JDgoju2aXiSFesuIypbymrHnokyqqxohrcGf9VZe4vnv8Y2
            kg+Z4DxkZ0U+ZUFcDUx39QVF5K9y5X/IQ1is3gvOvOg6tDp7bZjeuPA9vaIkQEpr
            FCMXKscWjZP1/zYBY0RME7zte+LI5m6T+kqdZTpgKconvCwm0c8yG3c0gQKBgQDE
            +Z+lxwWoqxuUtab1oOEe3Szs/HmbRKyZT+CO1eP02fD1fyttz98rHvJNHVkfXfpg
            k/rGAjD/vQGxZXz3l2pBBokmDQI8wmqiYBv7xHaaqiAq22YKZq6IOS9v1ySxCxcQ
            X1EQTxrhPgcGiqe+zfLKFtJ8Ai1z4lQ6YOmFiM48GQKBgCexCI5KqOZyqHTiY771
            7qyurXk4LWahFSZDGIH2KZDp/pi6yyvjdWDkx9lTDFgVCwN1Tqv7PEgLhJMsiVds
            doXjpGoqdYqqPDRvBuui5cx3j4tmJX+lidWmays+JCk54vyvJ0rm4JxCAZgN6XoS
            nlG1wg12bvzlmVtRVjDv67mH
            -----END PRIVATE KEY-----
            """;

    private static final String PUBLIC_KEY_BASE64 = String.join("",
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtppXJSispZc3lQsd0IMH",
            "ZOya66ZOpQO+xuk25145jQfRA259CKmGv3qpm4sVCssCxz0YtmWf+KY3H14Aj7Ca",
            "O9v8lZpQNKR0hbMYUutUWanyUbWcfo1v4rLct9Doplr8CbQh4DE3bFlBbSyduuLN",
            "9AokNWTVbTwswqGCf6jTkfq76BK/wjAlPWVPExkA9PHLHbqwVtvkmOwbsKNjZ8qY",
            "Bh/fIFS3RfQWOsVURrwr1h3zN5H6PIM0M+Gplcin18PBpZT0nJ9cHKbvZthFOxKH",
            "Ld+BlSE1cw+6r2Pox77bGYAaMVLy+h9zDJmsbc4dJ6wN17cOxV99n9GR97JwUHIf",
            "jwIDAQAB");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GoogleServiceAccountJwtSigner signer = new GoogleServiceAccountJwtSigner(objectMapper);

    @Test
    void sign_iss_scope_audを含む正しく署名されたJWTを生成する() throws Exception {
        GoogleServiceAccountKey key =
                new GoogleServiceAccountKey("svc@example.iam.gserviceaccount.com", PRIVATE_KEY_PEM, null);

        String jwt = signer.sign(key, "https://www.googleapis.com/auth/analytics.readonly",
                "https://oauth2.googleapis.com/token");

        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);

        JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals("svc@example.iam.gserviceaccount.com", claims.get("iss").asText());
        assertEquals("https://www.googleapis.com/auth/analytics.readonly", claims.get("scope").asText());
        assertEquals("https://oauth2.googleapis.com/token", claims.get("aud").asText());
        assertTrue(claims.get("exp").asLong() > claims.get("iat").asLong());

        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_BASE64)));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(signingInput);
        assertTrue(verifier.verify(signature));
    }

    @Test
    void sign_private_keyが無ければ例外() {
        GoogleServiceAccountKey key = new GoogleServiceAccountKey("svc@example.iam.gserviceaccount.com", null, null);

        org.junit.jupiter.api.Assertions.assertThrows(GoogleAnalyticsException.class,
                () -> signer.sign(key, "scope", "aud"));
    }
}
