package com.letsblog.publishing.controller;

import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.dto.TaxonomyResolveRequest;
import com.letsblog.publishing.dto.TaxonomyResolveResponse;
import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.ForbiddenException;
import com.letsblog.publishing.service.SiteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** issue #708(legacy-apiのTaxonomyControllerをpublishing-serviceへ移設)。 */
@ExtendWith(MockitoExtension.class)
class TaxonomyControllerTest {

    @Mock
    private SiteService siteService;

    @Mock
    private CmsAdapterFactory cmsAdapterFactory;

    @Mock
    private CmsAdapter cmsAdapter;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    @Test
    void resolve_サイトのCMSアダプタ経由でカテゴリ_タグを解決する() {
        TaxonomyController controller = new TaxonomyController(siteService, cmsAdapterFactory, adminAuthorizationService);
        CmsCredentials.WordPressCredentials credentials =
                new CmsCredentials.WordPressCredentials("https://example.test", "admin", "SSH");
        when(siteService.getCredentials("main")).thenReturn(credentials);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.resolveCategories(credentials, List.of("お知らせ"))).thenReturn(List.of("1"));
        when(cmsAdapter.resolveTags(credentials, List.of("新着"))).thenReturn(List.of("2"));

        TaxonomyResolveResponse response =
                controller.resolve(new TaxonomyResolveRequest("main", List.of("お知らせ"), List.of("新着")));

        assertEquals(List.of("1"), response.categoryIds());
        assertEquals(List.of("2"), response.tagIds());
    }

    @Test
    void resolve_サイトのプロジェクトメンバーでなければCMSへ問い合わせずに拒否する() {
        // issue #830: 対象サイトのWordPress上のカテゴリ/タグ一覧が「認証済みなら誰でも」見えていた。
        TaxonomyController controller = new TaxonomyController(siteService, cmsAdapterFactory, adminAuthorizationService);
        when(siteService.resolveProjectId("main")).thenReturn(7L);
        doThrow(new ForbiddenException("この操作にはプロジェクトメンバーまたはadmin権限が必要です"))
                .when(adminAuthorizationService).requireProjectMemberOrAdminForSite(7L);

        assertThrows(ForbiddenException.class,
                () -> controller.resolve(new TaxonomyResolveRequest("main", List.of("お知らせ"), List.of("新着"))));

        verifyNoInteractions(cmsAdapterFactory, cmsAdapter);
    }
}
