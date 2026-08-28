package com.letsblog.publishing.service;

import com.letsblog.publishing.aop.AuditLog;
import com.letsblog.publishing.client.ContentServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.domain.AuditLogAction;
import com.letsblog.publishing.messaging.DomainEventPublisher;
import org.springframework.stereotype.Service;

/**
 * WordPress投稿の削除(ゴミ箱移動)。行そのものは削除せず、statusを"trash"に更新して履歴として残す。
 * legacy-apiの{@code PostDeleteService}をpublishing-serviceへ移設したもの(issue #707、Epic #551 C6-1)。
 */
@Service
public class PostDeleteService {

    private final ProjectServiceClient projectServiceClient;
    private final CmsAdapterFactory cmsAdapterFactory;
    private final ContentServiceClient contentServiceClient;
    private final DomainEventPublisher domainEventPublisher;

    public PostDeleteService(ProjectServiceClient projectServiceClient, CmsAdapterFactory cmsAdapterFactory,
                              ContentServiceClient contentServiceClient,
                              DomainEventPublisher domainEventPublisher) {
        this.projectServiceClient = projectServiceClient;
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.contentServiceClient = contentServiceClient;
        this.domainEventPublisher = domainEventPublisher;
    }

    @AuditLog(action = AuditLogAction.POST_DELETED, resourceType = "POST")
    public void delete(String siteKey, String wpPostId) {
        ProjectServiceClient.SiteBridge site = projectServiceClient.getSiteByKey(siteKey);
        CmsCredentials credentials = projectServiceClient.getCredentials(siteKey).toCmsCredentials();
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());

        cmsAdapter.deletePost(credentials, wpPostId);

        contentServiceClient.markTrashed(site.id(), wpPostId);
        domainEventPublisher.publishPostDeleted(site.id(), wpPostId);
    }
}
