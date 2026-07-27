package com.letsblog.api.controller;

import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.dto.TaxonomyResolveRequest;
import com.letsblog.api.dto.TaxonomyResolveResponse;
import com.letsblog.api.service.SiteService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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
