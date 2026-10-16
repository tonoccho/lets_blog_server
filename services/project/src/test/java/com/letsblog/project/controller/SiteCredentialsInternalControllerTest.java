package com.letsblog.project.controller;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.SiteCredentialsResponse;
import com.letsblog.project.testsupport.ProjectWorld;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * SiteCredentialsInternalControllerの回帰テスト(issue #577受入基準: サイト認証情報の取得APIを
 * publishing-serviceから利用できるようにする)。
 *
 * <p>issue #1818: SiteService・CredentialCipher は実物で、差し替えるのはDBのリポジトリだけ。
 */
class SiteCredentialsInternalControllerTest {

    private final ProjectWorld world = new ProjectWorld();

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    void getCredentials_解決済み認証情報を返す() {
        Site site = world.addSite(1L, "my-site");
        when(world.siteRepository.findBySiteKey("site-1")).thenReturn(Optional.of(site));

        SiteCredentialsResponse response = new SiteCredentialsInternalController(world.siteService).getCredentials("site-1");

        assertEquals(1L, response.siteId());
        assertEquals(CmsType.WORDPRESS, response.cmsType());
        assertEquals("https://site1.example.com", response.credentials().get("baseUrl"));
        assertEquals("u", response.credentials().get("username"));
    }
}
