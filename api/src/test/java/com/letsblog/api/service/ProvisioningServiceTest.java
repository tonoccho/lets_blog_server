package com.letsblog.api.service;

import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProvisioningServiceTest {

    @Mock
    private CmsAdapterFactory cmsAdapterFactory;

    @Mock
    private CmsAdapter cmsAdapter;

    // CmsCredentialsはsealed interfaceのためモック化できず、実インスタンスを使う
    private final CmsCredentials credentials =
            new CmsCredentials.WordPressCredentials("https://example.com", "admin", "apppass");

    private ProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new ProvisioningService(cmsAdapterFactory);
        org.mockito.Mockito.lenient().when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
    }

    @Test
    void provisionSite_カテゴリ_タグ_著者すべて成功する() {
        when(cmsAdapter.provisionDefaultCategory(credentials)).thenReturn("cat-1");
        when(cmsAdapter.provisionDefaultTag(credentials)).thenReturn("tag-1");
        when(cmsAdapter.provisionAuthor(credentials, "actor@example.com")).thenReturn("author-1");

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
        when(cmsAdapter.provisionDefaultCategory(credentials)).thenReturn("cat-1");
        when(cmsAdapter.provisionDefaultTag(credentials)).thenReturn("tag-1");

        ProvisioningService.ProvisioningResult result = service.provisionSite(CmsType.WORDPRESS, credentials, null);

        assertNull(result.authorId);
        assertNull(result.authorError);
        verify(cmsAdapter, never()).provisionAuthor(any(), any());
    }

    @Test
    void provisionSite_カテゴリ作成失敗は部分的失敗として記録し続行する() {
        when(cmsAdapter.provisionDefaultCategory(credentials)).thenThrow(new RuntimeException("category failed"));
        when(cmsAdapter.provisionDefaultTag(credentials)).thenReturn("tag-1");
        when(cmsAdapter.provisionAuthor(credentials, "actor@example.com")).thenReturn("author-1");

        ProvisioningService.ProvisioningResult result =
                service.provisionSite(CmsType.WORDPRESS, credentials, "actor@example.com");

        assertNull(result.defaultCategoryId);
        assertEquals("category failed", result.categoryError);
        assertEquals("tag-1", result.defaultTagId);
        assertEquals("author-1", result.authorId);
    }

    @Test
    void provisionSite_全リソース失敗しても例外は投げない() {
        when(cmsAdapter.provisionDefaultCategory(credentials)).thenThrow(new RuntimeException("category failed"));
        when(cmsAdapter.provisionDefaultTag(credentials)).thenThrow(new RuntimeException("tag failed"));
        when(cmsAdapter.provisionAuthor(credentials, "actor@example.com")).thenThrow(new RuntimeException("author failed"));

        ProvisioningService.ProvisioningResult result =
                service.provisionSite(CmsType.WORDPRESS, credentials, "actor@example.com");

        assertEquals("category failed", result.categoryError);
        assertEquals("tag failed", result.tagError);
        assertEquals("author failed", result.authorError);
    }

    @Test
    void provisionSite_CMSアダプタ解決に失敗すると致命的エラーとして例外を投げる() {
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenThrow(new RuntimeException("unsupported"));

        assertThrows(ProvisioningException.class,
                () -> service.provisionSite(CmsType.WORDPRESS, credentials, "actor@example.com"));
    }
}
