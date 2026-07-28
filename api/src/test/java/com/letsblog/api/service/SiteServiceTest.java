package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.crypto.CredentialCipher;
import com.letsblog.api.domain.Site;
import com.letsblog.api.domain.User;
import com.letsblog.api.dto.SiteRegisterRequest;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.repository.SiteRepository;
import com.letsblog.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteServiceTest {

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private CredentialCipher credentialCipher;

    @Mock
    private CmsAdapterFactory cmsAdapterFactory;

    @Mock
    private CmsAdapter cmsAdapter;

    @Mock
    private ProvisioningService provisioningService;

    @Mock
    private UserRepository userRepository;

    private SiteService service;

    @BeforeEach
    void setUp() {
        service = new SiteService(siteRepository, credentialCipher, new ObjectMapper(), cmsAdapterFactory,
                provisioningService, userRepository);
        org.mockito.Mockito.lenient().when(provisioningService.provisionSite(any(), any(), any()))
                .thenReturn(new ProvisioningService.ProvisioningResult());
    }

    private SiteRegisterRequest wordPressRequest() {
        return new SiteRegisterRequest(
                "My Blog", "main", CmsType.WORDPRESS,
                Map.of("baseUrl", "https://example.com", "username", "admin", "appPassword", "secret"));
    }

    private void stubSaveSuccess() {
        when(siteRepository.existsBySiteKey("main")).thenReturn(false);
        when(credentialCipher.encrypt(any())).thenReturn(new byte[]{1, 2, 3});
        when(siteRepository.save(any(Site.class))).thenAnswer(invocation -> {
            Site s = invocation.getArgument(0);
            s.setId(1L);
            return s;
        });
    }

    @Test
    void register_疎通確認成功時はSUCCESSを返す() {
        stubSaveSuccess();
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.testConnection(any())).thenReturn(true);

        SiteResponse response = service.register(wordPressRequest(), null);

        assertEquals("SUCCESS", response.connectionCheckStatus());
        assertEquals(1L, response.id());
    }

    @Test
    void register_疎通確認失敗時もFAILEDとして登録自体は成功する() {
        stubSaveSuccess();
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.testConnection(any())).thenReturn(false);

        SiteResponse response = service.register(wordPressRequest(), null);

        assertEquals("FAILED", response.connectionCheckStatus());
        assertEquals(1L, response.id());
    }

    @Test
    void register_疎通確認処理が例外を投げてもFAILEDとして登録は成功する() {
        stubSaveSuccess();
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenThrow(new RuntimeException("adapter not found"));

        SiteResponse response = service.register(wordPressRequest(), null);

        assertEquals("FAILED", response.connectionCheckStatus());
    }

    @Test
    void register_既存siteKeyは例外() {
        when(siteRepository.existsBySiteKey("main")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.register(wordPressRequest(), null));
    }

    @Test
    void register_actorIdが指定されればプロビジョニングに操作者のメールアドレスを渡す() {
        stubSaveSuccess();
        User actor = new User();
        actor.setId(9L);
        actor.setEmail("actor@example.com");
        when(userRepository.findById(9L)).thenReturn(Optional.of(actor));
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.testConnection(any())).thenReturn(true);

        service.register(wordPressRequest(), 9L);

        verify(provisioningService, times(1)).provisionSite(eq(CmsType.WORDPRESS), any(), eq("actor@example.com"));
    }

    @Test
    void register_actorIdがnullならプロビジョニングのメールアドレスもnull() {
        stubSaveSuccess();
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.testConnection(any())).thenReturn(true);

        service.register(wordPressRequest(), null);

        verify(provisioningService, times(1)).provisionSite(eq(CmsType.WORDPRESS), any(), isNull());
    }

    @Test
    void register_プロビジョニングが致命的失敗の場合はサイトを保存しない() {
        when(siteRepository.existsBySiteKey("main")).thenReturn(false);
        when(provisioningService.provisionSite(any(), any(), any()))
                .thenThrow(new ProvisioningException("サイトのプロビジョニングに失敗しました", new RuntimeException("boom")));

        assertThrows(ProvisioningException.class, () -> service.register(wordPressRequest(), null));

        verify(siteRepository, never()).save(any());
    }

    @Test
    void reprovision_既存サイトの認証情報でプロビジョニングを再実行する() {
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("main");
        site.setCmsType(CmsType.WORDPRESS);
        site.setCredentialsEncrypted(new byte[]{1, 2, 3});
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(siteRepository.findBySiteKey("main")).thenReturn(Optional.of(site));
        when(credentialCipher.decrypt(any()))
                .thenReturn("{\"baseUrl\":\"https://example.com\",\"username\":\"admin\",\"appPassword\":\"secret\"}");

        service.reprovision(1L, null);

        verify(provisioningService, times(1)).provisionSite(eq(CmsType.WORDPRESS), any(), isNull());
    }

    @Test
    void reprovision_存在しないサイトは例外() {
        when(siteRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(SiteNotFoundException.class, () -> service.reprovision(99L, null));
    }
}
