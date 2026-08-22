package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.Post;
import com.letsblog.api.domain.Site;
import com.letsblog.api.repository.PostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WordPress投稿の削除(ゴミ箱移動)。行そのものは削除せず、statusを"trash"に更新して履歴として残す。
 */
@Service
public class PostDeleteService {

    private final SiteService siteService;
    private final CmsAdapterFactory cmsAdapterFactory;
    private final PostRepository postRepository;

    public PostDeleteService(SiteService siteService, CmsAdapterFactory cmsAdapterFactory,
                              PostRepository postRepository) {
        this.siteService = siteService;
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.postRepository = postRepository;
    }

    @AuditLog(action = AuditLogAction.POST_DELETED, resourceType = "POST")
    @Transactional
    public Post delete(String siteKey, String wpPostId) {
        Site site = siteService.getBySiteKey(siteKey);
        CmsCredentials credentials = siteService.getCredentials(siteKey);
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());

        cmsAdapter.deletePost(credentials, wpPostId);

        Post post = postRepository.findBySiteIdAndWpPostId(site.getId(), wpPostId).orElse(null);
        if (post != null) {
            post.setStatus("trash");
            postRepository.save(post);
        }
        return post;
    }
}
