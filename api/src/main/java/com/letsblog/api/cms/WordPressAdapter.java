package com.letsblog.api.cms;

import com.letsblog.api.cms.agent.WordPressAgentOperations;
import com.letsblog.api.cms.ssh.WordPressSshOperations;
import com.letsblog.api.provisioning.WordPressBulkManagementClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * WordPress向けCmsAdapter実装。SSH(wp-cli)または自動構築(managed)サイトのプロビジョニングエージェント
 * 経由のいずれかのトランスポートで操作する。
 */
@Component
@Slf4j
public class WordPressAdapter implements CmsAdapter {

    private static final String DEFAULT_CATEGORY_NAME = "Uncategorized";
    private static final String DEFAULT_TAG_NAME = "Let's Blog";

    private final WordPressSshOperations sshOperations;
    private final WordPressAgentOperations agentOperations;
    private final WordPressBulkManagementClient bulkManagementClient;

    public WordPressAdapter(WordPressSshOperations sshOperations,
            WordPressAgentOperations agentOperations, WordPressBulkManagementClient bulkManagementClient) {
        this.sshOperations = sshOperations;
        this.agentOperations = agentOperations;
        this.bulkManagementClient = bulkManagementClient;
    }

    @Override
    public CmsType supportedType() {
        return CmsType.WORDPRESS;
    }

    @Override
    public PostResult createOrUpdatePost(CmsCredentials credentials, PostContent content, String existingPostId) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        if (creds.isSsh()) {
            return sshOperations.createOrUpdatePost(creds, content, existingPostId);
        }
        if (creds.isAgent()) {
            return agentOperations.createOrUpdatePost(creds, content, existingPostId);
        }
        throw unsupportedTransport(creds);
    }

    @Override
    public boolean postExists(CmsCredentials credentials, String postId) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        if (creds.isSsh()) {
            return sshOperations.postExists(creds, postId);
        }
        if (creds.isAgent()) {
            return agentOperations.postExists(creds, postId);
        }
        throw unsupportedTransport(creds);
    }

    @Override
    public boolean mediaExists(CmsCredentials credentials, String mediaId) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        if (creds.isSsh()) {
            // WordPressではメディア(添付ファイル)もpost_type=attachmentのwp_postsレコードとして
            // 保存されているため、投稿の実在確認(`wp post get`)と同じ判定がそのまま使える。
            return sshOperations.postExists(creds, mediaId);
        }
        if (creds.isAgent()) {
            return agentOperations.postExists(creds, mediaId);
        }
        throw unsupportedTransport(creds);
    }

    @Override
    public MediaUploadResult uploadMedia(CmsCredentials credentials, String filename, String contentType, byte[] data) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        if (creds.isSsh()) {
            return sshOperations.uploadMedia(creds, filename, contentType, data);
        }
        if (creds.isAgent()) {
            return agentOperations.uploadMedia(creds, filename, contentType, data);
        }
        throw unsupportedTransport(creds);
    }

    @Override
    public List<String> resolveCategories(CmsCredentials credentials, List<String> names) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        if (creds.isSsh()) {
            return sshOperations.resolveCategories(creds, names);
        }
        if (creds.isAgent()) {
            return agentOperations.resolveCategories(creds, names);
        }
        throw unsupportedTransport(creds);
    }

    @Override
    public List<String> resolveTags(CmsCredentials credentials, List<String> names) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        if (creds.isSsh()) {
            return sshOperations.resolveTags(creds, names);
        }
        if (creds.isAgent()) {
            return agentOperations.resolveTags(creds, names);
        }
        throw unsupportedTransport(creds);
    }

    @Override
    public String provisionDefaultCategory(CmsCredentials credentials) {
        return resolveCategories(credentials, List.of(DEFAULT_CATEGORY_NAME)).get(0);
    }

    @Override
    public String provisionDefaultTag(CmsCredentials credentials) {
        return resolveTags(credentials, List.of(DEFAULT_TAG_NAME)).get(0);
    }

    @Override
    public String provisionAuthor(CmsCredentials credentials, AuthorProvisioningRequest request) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        if (creds.isSsh()) {
            return sshOperations.provisionAuthor(creds, request);
        }
        if (creds.isAgent()) {
            return agentOperations.provisionAuthor(creds, request);
        }
        throw unsupportedTransport(creds);
    }

    @Override
    public ConnectionCheckResult testConnection(CmsCredentials credentials) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        if (creds.isSsh()) {
            return sshOperations.testConnection(creds);
        }
        if (creds.isAgent()) {
            return agentOperations.testConnection(creds);
        }
        throw unsupportedTransport(creds);
    }

    @Override
    public boolean hasAuthorProvisioningCapability(CmsCredentials credentials) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        if (creds.isSsh()) {
            return sshOperations.hasAuthorProvisioningCapability(creds);
        }
        if (creds.isAgent()) {
            return agentOperations.hasAuthorProvisioningCapability(creds);
        }
        throw unsupportedTransport(creds);
    }

    @Override
    public void deletePost(CmsCredentials credentials, String postId) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        if (creds.isSsh()) {
            sshOperations.deletePost(creds, postId);
            return;
        }
        if (creds.isAgent()) {
            agentOperations.deletePost(creds, postId);
            return;
        }
        throw unsupportedTransport(creds);
    }

    @Override
    public AuthCookie generateAuthCookie(CmsCredentials credentials) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        if (creds.isAgent()) {
            return agentOperations.generateAuthCookie(creds);
        }
        if (creds.isSsh()) {
            return sshOperations.generateAuthCookie(creds);
        }
        throw unsupportedTransport(creds);
    }

    @Override
    public Optional<String> findAuthorIdByEmail(CmsCredentials credentials, String email) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        if (creds.isSsh()) {
            return sshOperations.findAuthorIdByEmail(creds, email);
        }
        if (creds.isAgent()) {
            return agentOperations.findAuthorIdByEmail(creds, email);
        }
        throw unsupportedTransport(creds);
    }

    @Override
    public List<String> listCategoryNames(CmsCredentials credentials) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        try {
            if (creds.isSsh()) {
                return sshOperations.listCategories(creds).stream()
                        .map(WordPressSshOperations.CategoryInfo::name)
                        .toList();
            }
            if (creds.isAgent()) {
                return bulkManagementClient.listCategories(creds.wpSlug()).stream()
                        .map(WordPressBulkManagementClient.CategoryInfo::name)
                        .toList();
            }
            throw unsupportedTransport(creds);
        } catch (RuntimeException e) {
            log.warn("カテゴリ一覧の取得に失敗しました: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<String> listTagNames(CmsCredentials credentials) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        try {
            if (creds.isSsh()) {
                return sshOperations.listTags(creds).stream()
                        .map(WordPressSshOperations.CategoryInfo::name)
                        .toList();
            }
            if (creds.isAgent()) {
                return bulkManagementClient.listTags(creds.wpSlug()).stream()
                        .map(WordPressBulkManagementClient.CategoryInfo::name)
                        .toList();
            }
            throw unsupportedTransport(creds);
        } catch (RuntimeException e) {
            log.warn("タグ一覧の取得に失敗しました: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<CategoryOption> listCategoriesWithParents(CmsCredentials credentials) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        try {
            if (creds.isSsh()) {
                List<WordPressSshOperations.CategoryInfo> categories = sshOperations.listCategories(creds);
                Map<String, String> nameBySlug = categories.stream()
                        .collect(Collectors.toMap(WordPressSshOperations.CategoryInfo::slug,
                                WordPressSshOperations.CategoryInfo::name, (a, b) -> a));
                return categories.stream()
                        .map(c -> new CategoryOption(c.name(), c.parentSlug() != null ? nameBySlug.get(c.parentSlug()) : null))
                        .toList();
            }
            if (creds.isAgent()) {
                List<WordPressBulkManagementClient.CategoryInfo> categories =
                        bulkManagementClient.listCategories(creds.wpSlug());
                Map<String, String> nameBySlug = categories.stream()
                        .collect(Collectors.toMap(WordPressBulkManagementClient.CategoryInfo::slug,
                                WordPressBulkManagementClient.CategoryInfo::name, (a, b) -> a));
                return categories.stream()
                        .map(c -> new CategoryOption(c.name(), c.parentSlug() != null ? nameBySlug.get(c.parentSlug()) : null))
                        .toList();
            }
            throw unsupportedTransport(creds);
        } catch (RuntimeException e) {
            log.warn("カテゴリ一覧(親子関係付き)の取得に失敗しました: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<CmsPostSummary> listPosts(CmsCredentials credentials, String postType) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        if (creds.isSsh()) {
            return sshOperations.listPosts(creds, postType);
        }
        if (creds.isAgent()) {
            return agentOperations.listPosts(creds, postType);
        }
        throw unsupportedTransport(creds);
    }

    @Override
    public void updatePostStatus(CmsCredentials credentials, String postId, String postType, String status) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        if (creds.isSsh()) {
            sshOperations.updatePostStatus(creds, postId, status);
            return;
        }
        if (creds.isAgent()) {
            agentOperations.updatePostStatus(creds, postId, status);
            return;
        }
        throw unsupportedTransport(creds);
    }

    @Override
    public void deletePost(CmsCredentials credentials, String postId, String postType) {
        deletePost(credentials, postId);
    }

    @Override
    public WpCliInstallResult installWpCli(CmsCredentials credentials) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        if (!creds.isSsh()) {
            throw new IllegalStateException("SSH接続が設定されていないサイトにはwp-cliをインストールできません");
        }
        return sshOperations.installWpCli(creds);
    }

    private IllegalStateException unsupportedTransport(CmsCredentials.WordPressCredentials creds) {
        return new IllegalStateException("サポートされていないWordPress接続方式です(transport=" + creds.transport() + ")");
    }
}
