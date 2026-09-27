package com.letsblog.analytics.service;

import com.letsblog.analytics.domain.AnalyticsCredentials;
import com.letsblog.analytics.repository.AnalyticsCredentialsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AnalyticsCredentialsServiceの回帰テスト(issue #571)。analytics_credentialsは初回書き込み時に
 * 遅延作成されること、GA/AdSenseそれぞれのクリア処理が全項目をnullにすることを検証する。
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsCredentialsServiceTest {

    @Mock
    private AnalyticsCredentialsRepository repository;

    private AnalyticsCredentialsService service() {
        return new AnalyticsCredentialsService(repository);
    }

    @Test
    void hasGoogleAnalyticsCredentials_未設定なら行が無くてもfalse() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());

        assertFalse(service().hasGoogleAnalyticsCredentials(1L));
    }

    @Test
    void setGaRefreshTokenEncrypted_新規行を作成して保存する() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(repository.save(any(AnalyticsCredentials.class))).thenAnswer(inv -> inv.getArgument(0));

        service().setGaRefreshTokenEncrypted(1L, new byte[]{1, 2, 3});

        org.mockito.ArgumentCaptor<AnalyticsCredentials> captor = org.mockito.ArgumentCaptor.forClass(AnalyticsCredentials.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        AnalyticsCredentials saved = captor.getValue();
        assertEquals(3, saved.getGaRefreshTokenEncrypted().length);
        assertTrue(saved.hasGoogleAnalyticsConnection());
        // プロパティ未選択なので、ダッシュボードの資格情報としては未設定のまま
        assertFalse(saved.hasGoogleAnalyticsCredentials());
    }

    @Test
    void hasGoogleAnalyticsCredentials_プロパティIDとリフレッシュトークンの両方が必要() {
        AnalyticsCredentials existing = new AnalyticsCredentials(1L);
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(AnalyticsCredentials.class))).thenAnswer(inv -> inv.getArgument(0));

        assertFalse(service().hasGoogleAnalyticsCredentials(1L));
        assertFalse(service().hasGaRefreshToken(1L));

        service().setGaPropertyId(1L, "123456789");
        assertFalse(existing.hasGoogleAnalyticsCredentials());
        assertEquals("123456789", service().getGaPropertyId(1L));

        service().setGaRefreshTokenEncrypted(1L, new byte[]{1});
        assertTrue(service().hasGoogleAnalyticsCredentials(1L));
        assertTrue(service().hasGaRefreshToken(1L));

        existing.setGaPropertyId(" ");
        assertFalse(existing.hasGoogleAnalyticsCredentials());
        existing.setGaPropertyId("1");
        existing.setGaRefreshTokenEncrypted(new byte[0]);
        assertFalse(existing.hasGoogleAnalyticsCredentials());
        existing.setGaRefreshTokenEncrypted(null);
        assertFalse(existing.hasGoogleAnalyticsConnection());
    }

    @Test
    void setGaOauthClient_シークレットがnullなら既存のシークレットを保つ() {
        AnalyticsCredentials existing = new AnalyticsCredentials(1L);
        existing.setGaOauthClientSecretEncrypted(new byte[]{9});
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(AnalyticsCredentials.class))).thenAnswer(inv -> inv.getArgument(0));

        service().setGaOauthClient(1L, "client-id", null);

        assertEquals("client-id", service().getGaOauthClientId(1L));
        assertTrue(service().hasGaOauthClientSecret(1L));
        assertEquals(1, service().getGaOauthClientSecretEncrypted(1L).length);

        service().setGaOauthClient(1L, "client-id-2", new byte[]{1, 2});
        assertEquals(2, existing.getGaOauthClientSecretEncrypted().length);
    }

    @Test
    void hasGaOauthClientSecret_行があってもシークレットがnullや空なら未保存() {
        AnalyticsCredentials existing = new AnalyticsCredentials(1L);
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));

        assertFalse(service().hasGaOauthClientSecret(1L));
        existing.setGaOauthClientSecretEncrypted(new byte[0]);
        assertFalse(service().hasGaOauthClientSecret(1L));
        existing.setGaOauthClientSecretEncrypted(new byte[]{1});
        assertTrue(service().hasGaOauthClientSecret(1L));
    }

    @Test
    void gaの読み取りは行が無ければ未設定として返す() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());

        assertNull(service().getGaOauthClientId(1L));
        assertFalse(service().hasGaOauthClientSecret(1L));
        assertNull(service().getGaOauthClientSecretEncrypted(1L));
        assertNull(service().getGaRefreshTokenEncrypted(1L));
        assertNull(service().getGaPropertyId(1L));
    }

    @Test
    void clearGoogleAnalyticsCredentials_リフレッシュトークンとプロパティとクライアントを破棄する() {
        AnalyticsCredentials existing = new AnalyticsCredentials(1L);
        existing.setGaPropertyId("123456789");
        existing.setGaRefreshTokenEncrypted(new byte[]{1, 2, 3});
        existing.setGaOauthClientId("client-id");
        existing.setGaOauthClientSecretEncrypted(new byte[]{4});
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(AnalyticsCredentials.class))).thenAnswer(inv -> inv.getArgument(0));

        service().clearGoogleAnalyticsCredentials(1L);

        assertFalse(existing.hasGoogleAnalyticsCredentials());
        assertFalse(existing.hasGoogleAnalyticsConnection());
        assertNull(existing.getGaPropertyId());
        assertNull(existing.getGaOauthClientId());
        assertNull(existing.getGaOauthClientSecretEncrypted());
    }

    @Test
    void clearAdSenseCredentials_全項目nullにする() {
        AnalyticsCredentials existing = new AnalyticsCredentials(1L);
        existing.setAdsenseAccountId("pub-1234567890123456");
        existing.setAdsenseRefreshTokenEncrypted(new byte[]{1, 2, 3});
        existing.setAdsenseOauthClientId("client-id");
        existing.setAdsenseOauthClientSecretEncrypted(new byte[]{4, 5, 6});
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(AnalyticsCredentials.class))).thenAnswer(inv -> inv.getArgument(0));

        service().clearAdSenseCredentials(1L);

        assertFalse(existing.hasAdsenseCredentials());
        assertFalse(existing.hasAdsenseOauthClient());
    }

    @Test
    void hasAdsenseCredentials_accountIdとrefreshTokenの両方が必要() {
        AnalyticsCredentials existing = new AnalyticsCredentials(1L);
        existing.setAdsenseAccountId("pub-1234567890123456");
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));

        assertFalse(service().hasAdsenseCredentials(1L));

        existing.setAdsenseRefreshTokenEncrypted(new byte[]{1, 2, 3});
        assertTrue(service().hasAdsenseCredentials(1L));
    }
}
