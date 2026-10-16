package com.letsblog.analytics.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AnalyticsCredentialsの「揃っているか」の判定(issue #1827)。各条件(null / 空白 / 空配列)の組み合わせごとに、
 * 結果が変わることを確かめる。
 */
class AnalyticsCredentialsTest {

    private static byte[] token(String kind) {
        return switch (kind) {
            case "null" -> null;
            case "empty" -> new byte[0];
            default -> new byte[] {1};
        };
    }

    private static String text(String kind) {
        return switch (kind) {
            case "null" -> null;
            case "blank" -> "  ";
            default -> "value";
        };
    }

    @ParameterizedTest(name = "[{index}] accountId={0}, refreshToken={1} -> {2}")
    @CsvSource({
            "value, set, true",
            "null, set, false",
            "blank, set, false",
            "value, null, false",
            "value, empty, false",
    })
    void hasAdsenseCredentials_パブリッシャーIDとリフレッシュトークンの両方が必要(
            String accountId, String refreshToken, boolean expected) {
        AnalyticsCredentials c = new AnalyticsCredentials(1L);
        c.setAdsenseAccountId(text(accountId));
        c.setAdsenseRefreshTokenEncrypted(token(refreshToken));

        assertEquals(expected, c.hasAdsenseCredentials());
    }

    @ParameterizedTest(name = "[{index}] refreshToken={0} -> {1}")
    @CsvSource({"set, true", "null, false", "empty, false"})
    void hasAdsenseConnection_リフレッシュトークンが保存されていれば連携済み(String refreshToken, boolean expected) {
        AnalyticsCredentials c = new AnalyticsCredentials(1L);
        c.setAdsenseRefreshTokenEncrypted(token(refreshToken));

        assertEquals(expected, c.hasAdsenseConnection());
    }

    @ParameterizedTest(name = "[{index}] secret={0} -> {1}")
    @CsvSource({"set, true", "null, false", "empty, false"})
    void hasAdsenseOauthClientSecret_空でないシークレットがあれば保存済み(String secret, boolean expected) {
        AnalyticsCredentials c = new AnalyticsCredentials(1L);
        c.setAdsenseOauthClientSecretEncrypted(token(secret));

        assertEquals(expected, c.hasAdsenseOauthClientSecret());
    }

    @ParameterizedTest(name = "[{index}] clientId={0}, secret={1} -> {2}")
    @CsvSource({
            "value, set, true",
            "null, set, false",
            "blank, set, false",
            "value, null, false",
            "value, empty, false",
    })
    void hasAdsenseOauthClient_クライアントIDとシークレットの両方が必要(
            String clientId, String secret, boolean expected) {
        AnalyticsCredentials c = new AnalyticsCredentials(1L);
        c.setAdsenseOauthClientId(text(clientId));
        c.setAdsenseOauthClientSecretEncrypted(token(secret));

        assertEquals(expected, c.hasAdsenseOauthClient());
    }

    @ParameterizedTest(name = "[{index}] propertyId={0}, refreshToken={1} -> {2}")
    @CsvSource({
            "value, set, true",
            "null, set, false",
            "blank, set, false",
            "value, null, false",
            "value, empty, false",
    })
    void hasGoogleAnalyticsCredentials_プロパティIDとリフレッシュトークンの両方が必要(
            String propertyId, String refreshToken, boolean expected) {
        AnalyticsCredentials c = new AnalyticsCredentials(1L);
        c.setGaPropertyId(text(propertyId));
        c.setGaRefreshTokenEncrypted(token(refreshToken));

        assertEquals(expected, c.hasGoogleAnalyticsCredentials());
    }

    @ParameterizedTest(name = "[{index}] refreshToken={0} -> {1}")
    @CsvSource({"set, true", "null, false", "empty, false"})
    void hasGoogleAnalyticsConnection_リフレッシュトークンが保存されていれば連携済み(String refreshToken, boolean expected) {
        AnalyticsCredentials c = new AnalyticsCredentials(1L);
        c.setGaRefreshTokenEncrypted(token(refreshToken));

        assertEquals(expected, c.hasGoogleAnalyticsConnection());
    }

    @Test
    void 作成時に作成日時と更新日時が揃い_更新時に更新日時だけが進む() throws InterruptedException {
        AnalyticsCredentials c = new AnalyticsCredentials(1L);

        c.onCreate();
        assertNotNull(c.getCreatedAt());
        assertEquals(c.getCreatedAt(), c.getUpdatedAt());

        Thread.sleep(5);
        c.onUpdate();
        assertTrue(c.getUpdatedAt().isAfter(c.getCreatedAt()));
        assertFalse(c.getCreatedAt().isAfter(c.getUpdatedAt()));
    }
}
