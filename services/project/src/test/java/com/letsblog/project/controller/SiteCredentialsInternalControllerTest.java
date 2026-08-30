package com.letsblog.project.controller;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.dto.SiteCredentialsResponse;
import com.letsblog.project.service.SiteService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * SiteCredentialsInternalControllerの回帰テスト(issue #577受入基準: サイト認証情報の取得APIを
 * publishing-serviceから利用できるようにする)。
 */
@ExtendWith(MockitoExtension.class)
class SiteCredentialsInternalControllerTest {

    @Mock
    private SiteService siteService;

    @Test
    void getCredentials_解決済み認証情報を返す() {
        when(siteService.getResolvedCredentials("my-site")).thenReturn(
                new SiteService.ResolvedSiteCredentials(1L, CmsType.WORDPRESS, Map.of("baseUrl", "https://example.com")));

        SiteCredentialsResponse response = new SiteCredentialsInternalController(siteService).getCredentials("my-site");

        assertEquals(1L, response.siteId());
        assertEquals(CmsType.WORDPRESS, response.cmsType());
        assertEquals("https://example.com", response.credentials().get("baseUrl"));
    }
}
