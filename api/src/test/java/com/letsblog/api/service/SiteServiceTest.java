package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.crypto.CredentialCipher;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.SiteRegisterRequest;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.repository.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

    private SiteService service;

    @BeforeEach
    void setUp() {
        service = new SiteService(siteRepository, credentialCipher, new ObjectMapper(), cmsAdapterFactory);
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

        SiteResponse response = service.register(wordPressRequest());

        assertEquals("SUCCESS", response.connectionCheckStatus());
        assertEquals(1L, response.id());
    }

    @Test
    void register_疎通確認失敗時もFAILEDとして登録自体は成功する() {
        stubSaveSuccess();
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.testConnection(any())).thenReturn(false);

        SiteResponse response = service.register(wordPressRequest());

        assertEquals("FAILED", response.connectionCheckStatus());
        assertEquals(1L, response.id());
    }

    @Test
    void register_疎通確認処理が例外を投げてもFAILEDとして登録は成功する() {
        stubSaveSuccess();
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenThrow(new RuntimeException("adapter not found"));

        SiteResponse response = service.register(wordPressRequest());

        assertEquals("FAILED", response.connectionCheckStatus());
    }

    @Test
    void register_既存siteKeyは例外() {
        when(siteRepository.existsBySiteKey("main")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.register(wordPressRequest()));
    }
}
