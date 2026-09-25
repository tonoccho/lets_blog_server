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
    void setGoogleAnalyticsCredentials_新規行を作成して保存する() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(repository.save(any(AnalyticsCredentials.class))).thenAnswer(inv -> inv.getArgument(0));

        service().setGoogleAnalyticsCredentials(1L, "123456789", new byte[]{1, 2, 3});

        org.mockito.ArgumentCaptor<AnalyticsCredentials> captor = org.mockito.ArgumentCaptor.forClass(AnalyticsCredentials.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        AnalyticsCredentials saved = captor.getValue();
        assertEquals("123456789", saved.getGaPropertyId());
        assertTrue(saved.hasGoogleAnalyticsCredentials());
    }

    @Test
    void clearGoogleAnalyticsCredentials_両方nullにする() {
        AnalyticsCredentials existing = new AnalyticsCredentials(1L);
        existing.setGaPropertyId("123456789");
        existing.setGaServiceAccountJsonEncrypted(new byte[]{1, 2, 3});
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(AnalyticsCredentials.class))).thenAnswer(inv -> inv.getArgument(0));

        service().clearGoogleAnalyticsCredentials(1L);

        assertFalse(existing.hasGoogleAnalyticsCredentials());
        assertNull(existing.getGaPropertyId());
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
