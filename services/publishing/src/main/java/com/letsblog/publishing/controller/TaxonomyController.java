package com.letsblog.publishing.controller;

import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.dto.TaxonomyResolveRequest;
import com.letsblog.publishing.dto.TaxonomyResolveResponse;
import com.letsblog.publishing.service.SiteService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * legacy-apiの{@code com.letsblog.api.controller.TaxonomyController}をpublishing-serviceへ
 * 移設したもの(issue #708、Epic #551 C6-2)。VSCode拡張がfront matter上のカテゴリ/タグ名を
 * WordPress側のIDへ解決するために呼ぶ。
 */
@RestController
public class TaxonomyController {

    private final SiteService siteService;
    private final CmsAdapterFactory cmsAdapterFactory;

    public TaxonomyController(SiteService siteService, CmsAdapterFactory cmsAdapterFactory) {
        this.siteService = siteService;
        this.cmsAdapterFactory = cmsAdapterFactory;
    }

    @PostMapping("/api/taxonomy/resolve")
    public TaxonomyResolveResponse resolve(@RequestBody TaxonomyResolveRequest request) {
        CmsCredentials credentials = siteService.getCredentials(request.site());
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
        return new TaxonomyResolveResponse(
                cmsAdapter.resolveCategories(credentials, request.categories()),
                cmsAdapter.resolveTags(credentials, request.tags())
        );
    }
}
