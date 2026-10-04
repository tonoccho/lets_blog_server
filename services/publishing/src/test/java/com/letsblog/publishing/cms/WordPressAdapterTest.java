package com.letsblog.publishing.cms;

import com.letsblog.publishing.cms.agent.WordPressAgentOperations;
import com.letsblog.publishing.cms.ssh.WordPressSshOperations;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
                "FTP", null, null, null, null, null, null, null);
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
    void testCreateOrUpdatePost_SSHトランスポートは投稿前にletsblogプラグインの導入を確かめる() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        PostContent content = new PostContent("Test Title", "test-slug", "<p>HTML</p>", "draft", null, null, null, null);
        when(sshOperations.createOrUpdatePost(creds, content, null))
                .thenReturn(new PostResult("123", "https://example.com/posts/test", "draft"));

        adapter.createOrUpdatePost(creds, content, null);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(sshOperations);
        order.verify(sshOperations).ensureLetsblogPlugin(creds);
        order.verify(sshOperations).createOrUpdatePost(creds, content, null);
    }

    @Test
    void testCreateOrUpdatePost_SSHでプラグイン導入に失敗しても投稿は続行する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        PostContent content = new PostContent("Test Title", "test-slug", "<p>HTML</p>", "draft", null, null, null, null);
        PostResult expected = new PostResult("123", "https://example.com/posts/test", "draft");
        org.mockito.Mockito.doThrow(new com.letsblog.publishing.cms.ssh.SshOperationException("activate failed"))
                .when(sshOperations).ensureLetsblogPlugin(creds);
        when(sshOperations.createOrUpdatePost(creds, content, null)).thenReturn(expected);

        assertEquals(expected, adapter.createOrUpdatePost(creds, content, null));
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

    // ---- issue #1431: スラッグでの既存投稿照会 ----

    @Test
    void findPostIdsBySlug_SSHはWordPressの保存形へ正規化したスラッグで委譲する() {
        when(sshOperations.findPostIdsBySlug(any(), org.mockito.ArgumentMatchers.eq("%e6%97%a5%e6%9c%ac-abc")))
                .thenReturn(List.of("42"));

        assertEquals(List.of("42"), adapter.findPostIdsBySlug(sshCredentials(), "日本 ABC"));
    }

    @Test
    void findPostIdsBySlug_managedはagentへ委譲する() {
        when(agentOperations.findPostIdsBySlug(any(), org.mockito.ArgumentMatchers.eq("my-slug")))
                .thenReturn(List.of("7"));

        assertEquals(List.of("7"), adapter.findPostIdsBySlug(agentCredentials(), "my-slug"));
        verify(sshOperations, never()).findPostIdsBySlug(any(), any());
    }

    @Test
    void findPostIdsBySlug_正規化後に空になるスラッグは照会せず空を返す() {
        assertEquals(List.of(), adapter.findPostIdsBySlug(sshCredentials(), "!!!"));
        verify(sshOperations, never()).findPostIdsBySlug(any(), any());
    }

    @Test
    void findPostIdsBySlug_未対応トランスポートは例外を投げる() {
        assertThrows(RuntimeException.class, () -> adapter.findPostIdsBySlug(unsupportedCredentials(), "my-slug"));
    }

    // ---- issue #1432: 内容ハッシュによるメディア照会 ----

    @Test
    void findMediaBySha256_SSHはsshOperationsへ委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        java.util.Set<String> hashes = java.util.Set.of("aa");
        Map<String, MediaUploadResult> expected = Map.of("aa", new MediaUploadResult("5", "u"));
        when(sshOperations.findMediaBySha256(creds, hashes)).thenReturn(expected);

        assertEquals(expected, adapter.findMediaBySha256(creds, hashes));
        verify(agentOperations, never()).findMediaBySha256(any(), any());
    }

    @Test
    void findMediaBySha256_managedはagentOperationsへ委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        java.util.Set<String> hashes = java.util.Set.of("aa");
        Map<String, MediaUploadResult> expected = Map.of("aa", new MediaUploadResult("5", "u"));
        when(agentOperations.findMediaBySha256(creds, hashes)).thenReturn(expected);

        assertEquals(expected, adapter.findMediaBySha256(creds, hashes));
        verify(sshOperations, never()).findMediaBySha256(any(), any());
    }

    @Test
    void findMediaBySha256_未対応トランスポートは例外を投げる() {
        assertThrows(RuntimeException.class,
                () -> adapter.findMediaBySha256(unsupportedCredentials(), java.util.Set.of("aa")));
    }

    // ---- issue #1557: letsblogプラグインの導入状態 ----

    private LetsblogPluginStatus installedStatus() {
        return new LetsblogPluginStatus(LetsblogPluginStatus.State.INSTALLED, "1.0.0",
                LetsblogPluginStatus.SUPPORTED_PROTOCOL_VERSION);
    }

    @Test
    void letsblogPluginStatus_SSHはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.letsblogPluginStatus(creds)).thenReturn(installedStatus());

        assertEquals(installedStatus(), adapter.letsblogPluginStatus(creds));
        verify(agentOperations, never()).letsblogPluginStatus(any());
    }

    @Test
    void letsblogPluginStatus_AGENTはWordPressAgentOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(agentOperations.letsblogPluginStatus(creds)).thenReturn(LetsblogPluginStatus.notInstalled());

        assertEquals(LetsblogPluginStatus.notInstalled(), adapter.letsblogPluginStatus(creds));
        verify(sshOperations, never()).letsblogPluginStatus(any());
    }

    @Test
    void letsblogPluginStatus_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class, () -> adapter.letsblogPluginStatus(unsupportedCredentials()));
    }

    @Test
    void installLetsblogPlugin_SSHは導入して導入後の状態を返す() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.installLetsblogPlugin(creds)).thenReturn(installedStatus());

        assertEquals(installedStatus(), adapter.installLetsblogPlugin(creds));
        verify(agentOperations, never()).installLetsblogPlugin(any());
    }

    @Test
    void installLetsblogPlugin_AGENTは導入して導入後の状態を返す() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(agentOperations.installLetsblogPlugin(creds)).thenReturn(installedStatus());

        assertEquals(installedStatus(), adapter.installLetsblogPlugin(creds));
        verify(sshOperations, never()).installLetsblogPlugin(any());
    }

    @Test
    void installLetsblogPlugin_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class, () -> adapter.installLetsblogPlugin(unsupportedCredentials()));
    }

    @Test
    void requireLetsblogPlugin_導入済みなら何も投げない() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(agentOperations.letsblogPluginStatus(creds)).thenReturn(installedStatus());

        adapter.requireLetsblogPlugin(creds);
    }

    @Test
    void requireLetsblogPlugin_未導入なら再導入を案内する例外を投げる() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.letsblogPluginStatus(creds)).thenReturn(LetsblogPluginStatus.notInstalled());

        LetsblogPluginUnavailableException e =
                assertThrows(LetsblogPluginUnavailableException.class, () -> adapter.requireLetsblogPlugin(creds));
        assertEquals(true, e.getMessage().contains("再導入"));
    }

    @Test
    void requireLetsblogPlugin_要更新でも例外を投げる() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(agentOperations.letsblogPluginStatus(creds)).thenReturn(
                new LetsblogPluginStatus(LetsblogPluginStatus.State.NEEDS_UPDATE, "0.9.0", 0));

        LetsblogPluginUnavailableException e =
                assertThrows(LetsblogPluginUnavailableException.class, () -> adapter.requireLetsblogPlugin(creds));
        assertEquals(LetsblogPluginStatus.State.NEEDS_UPDATE, e.getStatus().state());
    }

    // ---- issue #1558: letsblogプラグインへの同期(wp-cliだけ) ----

    @Test
    void syncLetsblogPlugin_SSHはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.syncLetsblogPlugin(creds, "{}", "h1")).thenReturn("h1");

        assertEquals("h1", adapter.syncLetsblogPlugin(creds, "{}", "h1"));
        verify(agentOperations, never()).syncLetsblogPlugin(any(), any(), any());
    }

    @Test
    void syncLetsblogPlugin_AGENTはWordPressAgentOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(agentOperations.syncLetsblogPlugin(creds, "{}", "h1")).thenReturn("h1");

        assertEquals("h1", adapter.syncLetsblogPlugin(creds, "{}", "h1"));
        verify(sshOperations, never()).syncLetsblogPlugin(any(), any(), any());
    }

    @Test
    void syncLetsblogPlugin_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class,
                () -> adapter.syncLetsblogPlugin(unsupportedCredentials(), "{}", "h1"));
    }

    // ---- issue #1561: 署名付きプレビュー URL の発行(wp-cliだけ) ----

    @Test
    void createSignedPreview_SSHはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        SignedPreview expected = new SignedPreview("https://x/?letsblog_preview=t", 1L);
        when(sshOperations.createSignedPreview(creds, "{}", 600)).thenReturn(expected);

        assertEquals(expected, adapter.createSignedPreview(creds, "{}", 600));
        verify(agentOperations, never()).createSignedPreview(any(), any(), any());
    }

    @Test
    void createSignedPreview_AGENTはWordPressAgentOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        SignedPreview expected = new SignedPreview("https://x/?letsblog_preview=t", 1L);
        when(agentOperations.createSignedPreview(creds, "{}", null)).thenReturn(expected);

        assertEquals(expected, adapter.createSignedPreview(creds, "{}", null));
        verify(sshOperations, never()).createSignedPreview(any(), any(), any());
    }

    @Test
    void createSignedPreview_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class,
                () -> adapter.createSignedPreview(unsupportedCredentials(), "{}", null));
    }
}
