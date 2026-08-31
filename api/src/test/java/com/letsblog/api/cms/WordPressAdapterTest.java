package com.letsblog.api.cms;

import com.letsblog.api.cms.agent.WordPressAgentOperations;
import com.letsblog.api.cms.ssh.WordPressSshOperations;
import com.letsblog.api.provisioning.WordPressBulkManagementClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WordPressAdapterの回帰テスト。SSH(wp-cli)/managed(agent)のいずれかのトランスポートへ委譲する
 * ことのみを検証する(REST(wp-json)呼び出しはissue #518で廃止済み)。
 */
class WordPressAdapterTest {

    private WordPressAdapter adapter;
    private WordPressSshOperations sshOperations;
    private WordPressAgentOperations agentOperations;
    private WordPressBulkManagementClient bulkManagementClient;

    @BeforeEach
    void setUp() {
        sshOperations = mock(WordPressSshOperations.class);
        agentOperations = mock(WordPressAgentOperations.class);
        bulkManagementClient = mock(WordPressBulkManagementClient.class);
        adapter = new WordPressAdapter(sshOperations, agentOperations, bulkManagementClient);
    }

    private CmsCredentials.WordPressCredentials sshCredentials() {
        return new CmsCredentials.WordPressCredentials(
                "https://example.com", null,
                "SSH", "ssh.example.com", 22, "deploy", "/var/www/html", "PRIVATE-KEY-PEM", null, null);
    }

    private CmsCredentials.WordPressCredentials agentCredentials() {
        return new CmsCredentials.WordPressCredentials(
                "http://wordpress/sites/main", "admin",
                "AGENT", null, null, null, null, null, null, "main");
    }

    private CmsCredentials.WordPressCredentials unsupportedCredentials() {
        return new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin",
                "REST", null, null, null, null, null, null, null);
    }

    @Test
    void testSupportedType() {
        assertEquals(CmsType.WORDPRESS, adapter.supportedType());
    }

    @Test
    void testTestConnection_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.testConnection(creds)).thenReturn(ConnectionCheckResult.success());

        ConnectionCheckResult result = adapter.testConnection(creds);

        assertEquals(true, result.ok());
        verify(sshOperations).testConnection(creds);
    }

    @Test
    void testTestConnection_AGENTトランスポートはWordPressAgentOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(agentOperations.testConnection(creds)).thenReturn(ConnectionCheckResult.success());

        ConnectionCheckResult result = adapter.testConnection(creds);

        assertEquals(true, result.ok());
        verify(agentOperations).testConnection(creds);
        verify(sshOperations, never()).testConnection(any());
    }

    @Test
    void testTestConnection_未対応トランスポートは例外() {
        CmsCredentials.WordPressCredentials creds = unsupportedCredentials();

        assertThrows(IllegalStateException.class, () -> adapter.testConnection(creds));
        verify(sshOperations, never()).testConnection(any());
        verify(agentOperations, never()).testConnection(any());
    }

    @Test
    void testGenerateAuthCookie_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        AuthCookie cookie = new AuthCookie("wordpress_logged_in_x", "value");
        when(sshOperations.generateAuthCookie(creds)).thenReturn(cookie);

        AuthCookie result = adapter.generateAuthCookie(creds);

        assertEquals(cookie, result);
        verify(sshOperations).generateAuthCookie(creds);
        verify(agentOperations, never()).generateAuthCookie(any());
    }

    @Test
    void testGenerateAuthCookie_AGENTトランスポートはWordPressAgentOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        AuthCookie cookie = new AuthCookie("wordpress_logged_in_x", "value");
        when(agentOperations.generateAuthCookie(creds)).thenReturn(cookie);

        AuthCookie result = adapter.generateAuthCookie(creds);

        assertEquals(cookie, result);
        verify(agentOperations).generateAuthCookie(creds);
        verify(sshOperations, never()).generateAuthCookie(any());
    }

    @Test
    void testGenerateAuthCookie_未対応トランスポートは例外() {
        CmsCredentials.WordPressCredentials creds = unsupportedCredentials();

        assertThrows(IllegalStateException.class, () -> adapter.generateAuthCookie(creds));
        verify(sshOperations, never()).generateAuthCookie(any());
        verify(agentOperations, never()).generateAuthCookie(any());
    }

    @Test
    void testCreateOrUpdatePost_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        PostContent content = new PostContent("Test Title", "test-slug", "<p>HTML</p>", "draft", null, null, null, null);
        PostResult expected = new PostResult("123", "https://example.com/posts/test", "draft");
        when(sshOperations.createOrUpdatePost(creds, content, null)).thenReturn(expected);

        PostResult result = adapter.createOrUpdatePost(creds, content, null);

        assertEquals(expected, result);
        verify(agentOperations, never()).createOrUpdatePost(any(), any(), any());
    }

    @Test
    void testCreateOrUpdatePost_AGENTトランスポートはWordPressAgentOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        PostContent content = new PostContent("Test Title", "test-slug", "<p>HTML</p>", "draft", null, null, null, null);
        PostResult expected = new PostResult("123", "https://example.com/posts/test", "draft");
        when(agentOperations.createOrUpdatePost(creds, content, null)).thenReturn(expected);

        PostResult result = adapter.createOrUpdatePost(creds, content, null);

        assertEquals(expected, result);
        verify(sshOperations, never()).createOrUpdatePost(any(), any(), any());
    }

    @Test
    void testPostExists_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.postExists(creds, "42")).thenReturn(false);

        boolean result = adapter.postExists(creds, "42");

        assertEquals(false, result);
        verify(sshOperations).postExists(creds, "42");
        verify(agentOperations, never()).postExists(any(), any());
    }

    @Test
    void testPostExists_AGENTトランスポートはWordPressAgentOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(agentOperations.postExists(creds, "42")).thenReturn(true);

        boolean result = adapter.postExists(creds, "42");

        assertEquals(true, result);
        verify(agentOperations).postExists(creds, "42");
        verify(sshOperations, never()).postExists(any(), any());
    }

    @Test
    void testMediaExists_SSHトランスポートはWordPressSshOperationsのpostExistsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.postExists(creds, "11")).thenReturn(false);

        boolean result = adapter.mediaExists(creds, "11");

        assertEquals(false, result);
        verify(sshOperations).postExists(creds, "11");
        verify(agentOperations, never()).postExists(any(), any());
    }

    @Test
    void testMediaExists_AGENTトランスポートはWordPressAgentOperationsのpostExistsに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(agentOperations.postExists(creds, "11")).thenReturn(true);

        boolean result = adapter.mediaExists(creds, "11");

        assertEquals(true, result);
        verify(agentOperations).postExists(creds, "11");
        verify(sshOperations, never()).postExists(any(), any());
    }

    @Test
    void testUploadMedia_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        byte[] imageData = new byte[]{1, 2, 3};
        MediaUploadResult expected = new MediaUploadResult("456", "https://example.com/uploads/image.png");
        when(sshOperations.uploadMedia(creds, "image.png", "image/png", imageData)).thenReturn(expected);

        MediaUploadResult result = adapter.uploadMedia(creds, "image.png", "image/png", imageData);

        assertEquals(expected, result);
        verify(agentOperations, never()).uploadMedia(any(), any(), any(), any());
    }

    @Test
    void testResolveCategories_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.resolveCategories(creds, List.of("Technology"))).thenReturn(List.of("1"));

        List<String> ids = adapter.resolveCategories(creds, List.of("Technology"));

        assertEquals(List.of("1"), ids);
    }

    @Test
    void testResolveTags_AGENTトランスポートはWordPressAgentOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(agentOperations.resolveTags(creds, List.of("java"))).thenReturn(List.of("9"));

        List<String> ids = adapter.resolveTags(creds, List.of("java"));

        assertEquals(List.of("9"), ids);
    }

    @Test
    void testFindAuthorIdByEmail_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.findAuthorIdByEmail(creds, "author@example.com"))
                .thenReturn(java.util.Optional.of("9"));

        java.util.Optional<String> authorId = adapter.findAuthorIdByEmail(creds, "author@example.com");

        assertEquals("9", authorId.get());
    }

    @Test
    void testListCategoryNames_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.listCategories(creds)).thenReturn(List.of(
                new WordPressSshOperations.CategoryInfo("1", "お知らせ", "news", null, "")));

        List<String> names = adapter.listCategoryNames(creds);

        assertEquals(List.of("お知らせ"), names);
    }

    @Test
    void testListCategoryNames_AGENTトランスポートはWordPressBulkManagementClientに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(bulkManagementClient.listCategories(creds.wpSlug())).thenReturn(List.of(
                new WordPressBulkManagementClient.CategoryInfo("技術", "tech", null, "")));

        List<String> names = adapter.listCategoryNames(creds);

        assertEquals(List.of("技術"), names);
    }

    @Test
    void testListCategoryNames_取得失敗時は空リストを返す() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.listCategories(creds)).thenThrow(new RuntimeException("boom"));

        List<String> names = adapter.listCategoryNames(creds);

        assertEquals(List.of(), names);
    }

    @Test
    void testListTagNames_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.listTags(creds)).thenReturn(List.of(
                new WordPressSshOperations.CategoryInfo("1", "Java", "java", null, "")));

        List<String> names = adapter.listTagNames(creds);

        assertEquals(List.of("Java"), names);
    }

    @Test
    void testListTagNames_AGENTトランスポートはWordPressBulkManagementClientに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(bulkManagementClient.listTags(creds.wpSlug())).thenReturn(List.of(
                new WordPressBulkManagementClient.CategoryInfo("AWS", "aws", null, "")));

        List<String> names = adapter.listTagNames(creds);

        assertEquals(List.of("AWS"), names);
    }

    @Test
    void testListCategoriesWithParents_SSHトランスポートはparentSlugから親カテゴリ名を解決する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.listCategories(creds)).thenReturn(List.of(
                new WordPressSshOperations.CategoryInfo("1", "技術", "tech", null, ""),
                new WordPressSshOperations.CategoryInfo("2", "Java", "java", "tech", "")));

        List<CmsAdapter.CategoryOption> options = adapter.listCategoriesWithParents(creds);

        assertEquals(List.of(
                new CmsAdapter.CategoryOption("技術", null),
                new CmsAdapter.CategoryOption("Java", "技術")), options);
    }

    @Test
    void testListCategoriesWithParents_AGENTトランスポートはparentSlugから親カテゴリ名を解決する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(bulkManagementClient.listCategories(creds.wpSlug())).thenReturn(List.of(
                new WordPressBulkManagementClient.CategoryInfo("技術", "tech", null, ""),
                new WordPressBulkManagementClient.CategoryInfo("Java", "java", "tech", "")));

        List<CmsAdapter.CategoryOption> options = adapter.listCategoriesWithParents(creds);

        assertEquals(List.of(
                new CmsAdapter.CategoryOption("技術", null),
                new CmsAdapter.CategoryOption("Java", "技術")), options);
    }

    @Test
    void testListCategoriesWithParents_取得失敗時は空リストを返す() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.listCategories(creds)).thenThrow(new RuntimeException("boom"));

        List<CmsAdapter.CategoryOption> options = adapter.listCategoriesWithParents(creds);

        assertEquals(List.of(), options);
    }

    @Test
    void testDeletePost_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();

        adapter.deletePost(creds, "123");

        verify(sshOperations).deletePost(creds, "123");
    }

    @Test
    void testDeletePost_AGENTトランスポートはWordPressAgentOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();

        adapter.deletePost(creds, "123");

        verify(agentOperations).deletePost(creds, "123");
        verify(sshOperations, never()).deletePost(any(), any());
    }

    @Test
    void testDeletePost_postTypeあり版はpostId版へ委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();

        adapter.deletePost(creds, "123", "post");

        verify(sshOperations).deletePost(creds, "123");
    }

    @Test
    void testProvisionAuthor_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        AuthorProvisioningRequest request = AuthorProvisioningRequest.of("author@example.com");
        when(sshOperations.provisionAuthor(creds, request)).thenReturn("7");

        String authorId = adapter.provisionAuthor(creds, request);

        assertEquals("7", authorId);
    }

    @Test
    void testHasAuthorProvisioningCapability_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.hasAuthorProvisioningCapability(creds)).thenReturn(true);

        assertEquals(true, adapter.hasAuthorProvisioningCapability(creds));
    }

    @Test
    void testInstallWpCli_SSH以外は例外() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();

        assertThrows(IllegalStateException.class, () -> adapter.installWpCli(creds));
    }
}
