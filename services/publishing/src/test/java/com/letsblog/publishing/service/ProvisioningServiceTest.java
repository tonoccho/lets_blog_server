package com.letsblog.publishing.service;

import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.testsupport.PublishingWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProvisioningServiceの回帰テスト(issue #577 stage2でlegacy-api向けに作成、issue #710でpublishing-serviceへ移管)。
 *
 * <p>issue #1815で書き直し: CmsAdapterFactory・WordPressAdapterは実物を使い、外部との通信(SSH)だけを
 * {@code FakeSshCommandExecutor}(手書きフェイク)で差し替える。
 */
class ProvisioningServiceTest {

    private final CmsCredentials credentials = new CmsCredentials.WordPressCredentials(
            "https://example.com", "admin", "SSH", "ssh.example.com", 22, "deploy", "/var/www/html",
            "PRIVATE-KEY-PEM", null, null);

    private PublishingWorld world;
    private ProvisioningService service;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        service = new ProvisioningService(world.cmsAdapterFactory);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void provisionSite_カテゴリ_タグ_著者すべて成功する() {
        world.ssh.whenOk("term list", "[]")
                .whenOk("term create 'category'", "cat-1").whenOk("term create 'post_tag'", "tag-1")
                .whenOk("user list", "[]").whenOk("user create", "author-1").whenOk("user update", "");

        ProvisioningService.ProvisioningResult result =
                service.provisionSite(CmsType.WORDPRESS, credentials, "actor@example.com");

        assertEquals("cat-1", result.defaultCategoryId);
        assertEquals("tag-1", result.defaultTagId);
        assertEquals("author-1", result.authorId);
        assertNull(result.categoryError);
        assertNull(result.tagError);
        assertNull(result.authorError);
    }

    @Test
    void provisionSite_actorEmailがnullなら著者プロビジョニングをスキップする() {
        world.ssh.whenOk("term list", "[]")
                .whenOk("term create 'category'", "cat-1").whenOk("term create 'post_tag'", "tag-1");

        ProvisioningService.ProvisioningResult result = service.provisionSite(CmsType.WORDPRESS, credentials, null);

        assertNull(result.authorId);
        assertNull(result.authorError);
        assertFalse(world.ssh.ran("user "), "著者関連のコマンドは実行されない: " + world.ssh.commands);
    }

    @Test
    void provisionSite_カテゴリ作成失敗は部分的失敗として記録し続行する() {
        world.ssh.whenOk("term list", "[]")
                .whenFail("term create 'category'", "category failed").whenOk("term create 'post_tag'", "tag-1")
                .whenOk("user list", "[]").whenOk("user create", "author-1").whenOk("user update", "");

        ProvisioningService.ProvisioningResult result =
                service.provisionSite(CmsType.WORDPRESS, credentials, "actor@example.com");

        assertNull(result.defaultCategoryId);
        assertNotNull(result.categoryError);
        assertTrue(result.categoryError.contains("category failed"), result.categoryError);
        assertEquals("tag-1", result.defaultTagId);
        assertEquals("author-1", result.authorId);
    }

    @Test
    void provisionSite_全リソース失敗しても例外は投げない() {
        world.ssh.whenOk("term list", "[]")
                .whenFail("term create 'category'", "category failed")
                .whenFail("term create 'post_tag'", "tag failed")
                .whenOk("user list", "[]").whenFail("user create", "author failed");

        ProvisioningService.ProvisioningResult result =
                service.provisionSite(CmsType.WORDPRESS, credentials, "actor@example.com");

        assertTrue(result.categoryError.contains("category failed"), result.categoryError);
        assertTrue(result.tagError.contains("tag failed"), result.tagError);
        assertTrue(result.authorError.contains("author failed"), result.authorError);
    }

    @Test
    void provisionSite_CMSアダプタ解決に失敗すると致命的エラーとして例外を投げる() {
        ProvisioningService withoutAdapters = new ProvisioningService(new CmsAdapterFactory(List.of()));

        assertThrows(ProvisioningException.class,
                () -> withoutAdapters.provisionSite(CmsType.WORDPRESS, credentials, "actor@example.com"));
    }
}
