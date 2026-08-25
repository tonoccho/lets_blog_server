package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.client.ContentServiceClient;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.Site;
import com.letsblog.api.messaging.DomainEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WordPress投稿の削除(ゴミ箱移動)。行そのものは削除せず、statusを"trash"に更新して履歴として残す。
 * postsテーブルの所有権がcontent-serviceへ移った(issue #576)ため、statusの更新は
 * {@link ContentServiceClient}経由の内部ブリッジで行う。
 */
@Service
public class PostDeleteService {

    private final SiteService siteService;
    private final CmsAdapterFactory cmsAdapterFactory;
    private final ContentServiceClient contentServiceClient;
    private final DomainEventPublisher domainEventPublisher;

    public PostDeleteService(SiteService siteService, CmsAdapterFactory cmsAdapterFactory,
                              ContentServiceClient contentServiceClient,
                              DomainEventPublisher domainEventPublisher) {
        this.siteService = siteService;
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.contentServiceClient = contentServiceClient;
        this.domainEventPublisher = domainEventPublisher;
    }

    @AuditLog(action = AuditLogAction.POST_DELETED, resourceType = "POST")
    @Transactional
    public void delete(String siteKey, String wpPostId) {
        Site site = siteService.getBySiteKey(siteKey);
        CmsCredentials credentials = siteService.getCredentials(siteKey);
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());

        cmsAdapter.deletePost(credentials, wpPostId);

        contentServiceClient.markTrashed(site.getId(), wpPostId);
        domainEventPublisher.publishPostDeleted(site.getId(), wpPostId);
    }
}
