package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.crypto.CredentialCipher;
import com.letsblog.api.domain.Site;
import com.letsblog.api.domain.User;
import com.letsblog.api.dto.SiteConnectionCheckResult;
import com.letsblog.api.dto.SiteRegisterRequest;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.dto.SiteUpdateRequest;
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

    private Site buildExternalSite() {
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("main");
        site.setName("My Blog");
        site.setCmsType(CmsType.WORDPRESS);
        site.setManagedWordpress(false);
        site.setCredentialsEncrypted(new byte[]{1, 2, 3});
        return site;
    }

    @Test
    void update_名前のみ変更できる() {
        Site site = buildExternalSite();
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(siteRepository.save(any(Site.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SiteResponse response = service.update(1L, new SiteUpdateRequest("New Name", null));

        assertEquals("New Name", response.name());
        assertEquals(null, response.connectionCheckStatus());
    }

    @Test
    void update_credentialsは指定フィールドのみ上書きし既存値を保持する() {
        Site site = buildExternalSite();
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(credentialCipher.decrypt(any()))
                .thenReturn("{\"baseUrl\":\"https://example.com\",\"username\":\"admin\",\"appPassword\":\"old-pass\"}");
        when(credentialCipher.encrypt(any())).thenReturn(new byte[]{9, 9, 9});
        when(siteRepository.save(any(Site.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.testConnection(any())).thenReturn(true);

        SiteResponse response = service.update(1L, new SiteUpdateRequest(null, Map.of("appPassword", "new-pass")));

        assertEquals("SUCCESS", response.connectionCheckStatus());
        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(credentialCipher).encrypt(jsonCaptor.capture());
        assertEquals(true, jsonCaptor.getValue().contains("\"baseUrl\":\"https://example.com\""));
        assertEquals(true, jsonCaptor.getValue().contains("\"appPassword\":\"new-pass\""));
    }

    @Test
    void update_managedWordpressサイトのcredentials編集は例外() {
        Site site = buildExternalSite();
        site.setManagedWordpress(true);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        assertThrows(IllegalArgumentException.class,
                () -> service.update(1L, new SiteUpdateRequest(null, Map.of("appPassword", "x"))));
    }

    @Test
    void update_存在しないサイトは例外() {
        when(siteRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(SiteNotFoundException.class, () -> service.update(99L, new SiteUpdateRequest("x", null)));
    }

    @Test
    void checkConnection_成功時true() {
        Site site = buildExternalSite();
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(credentialCipher.decrypt(any()))
                .thenReturn("{\"baseUrl\":\"https://example.com\",\"username\":\"admin\",\"appPassword\":\"secret\"}");
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.testConnection(any())).thenReturn(true);
        when(cmsAdapter.hasAuthorProvisioningCapability(any())).thenReturn(true);

        SiteConnectionCheckResult result = service.checkConnection(1L);

        assertEquals(true, result.connectionOk());
        assertEquals(true, result.hasAdminCapability());
    }

    @Test
    void checkConnection_接続失敗時は管理者権限を判定しない() {
        Site site = buildExternalSite();
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(credentialCipher.decrypt(any()))
                .thenReturn("{\"baseUrl\":\"https://example.com\",\"username\":\"admin\",\"appPassword\":\"secret\"}");
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.testConnection(any())).thenReturn(false);

        SiteConnectionCheckResult result = service.checkConnection(1L);

        assertEquals(false, result.connectionOk());
        assertEquals(null, result.hasAdminCapability());
    }

    @Test
    void checkConnection_例外発生時はfalseかつhasAdminCapabilityはnull() {
        Site site = buildExternalSite();
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(credentialCipher.decrypt(any()))
                .thenReturn("{\"baseUrl\":\"https://example.com\",\"username\":\"admin\",\"appPassword\":\"secret\"}");
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenThrow(new RuntimeException("boom"));

        SiteConnectionCheckResult result = service.checkConnection(1L);

        assertEquals(false, result.connectionOk());
        assertEquals(null, result.hasAdminCapability());
    }
}
