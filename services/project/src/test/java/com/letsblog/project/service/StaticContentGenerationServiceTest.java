package com.letsblog.project.service;

import com.letsblog.project.domain.Site;
import com.letsblog.project.domain.StaticContent;
import com.letsblog.project.domain.StaticContentType;
import com.letsblog.project.dto.StaticContentResponse;
import com.letsblog.project.repository.StaticContentRepository;
import com.letsblog.project.testsupport.ProjectWorld;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StaticContentGenerationServiceの回帰テスト(issue #577 stage2、legacy-apiから移設)。
 *
 * <p>issue #1817: SiteService・各クライアント(AiGenerationClient・WordPressAgentPluginsClient・
 * CmsProvisioningBridgeClient)は実物。差し替えるのはDB(リポジトリ)とHTTPの向こう(ai-service・provision-agent・
 * publishing-service)だけで、LLMへ送った依頼はフェイクHTTPサーバーの受信記録で確かめる。
 */
@ExtendWith(MockitoExtension.class)
class StaticContentGenerationServiceTest {

    private static final String PLUGINS = "/plugins";
    private static final String CMS_PLUGINS = "/api/internal/project/cms/list-active-plugins";

    @Mock
    private StaticContentRepository staticContentRepository;

    private final ProjectWorld world = new ProjectWorld();

    @AfterEach
    void close() {
        world.close();
    }

    private StaticContentGenerationService service() {
        return new StaticContentGenerationService(
                world.siteRepository, world.siteService, world.wordPressAgentPluginsClient, world.cmsBridgeClient,
                world.aiGenerationClient, staticContentRepository);
    }

    private void agentPlugins(String... activeNames) {
        List<Map<String, String>> plugins = new java.util.ArrayList<>();
        for (String name : activeNames) {
            plugins.add(Map.of("name", name, "status", "active"));
        }
        plugins.add(Map.of("name", "休止中", "status", "inactive"));
        world.respond("POST", PLUGINS, Map.of("plugins", plugins));
    }

    private String sentPrompt() {
        return world.bodiesOf("POST", "/api/internal/ai/generate").get(0).get("prompt").asText();
    }

    @Test
    void generate_managedサイトはエージェント経由でプラグイン取得しLLM応答をtextブロックから抽出して保存する() {
        Site site = world.addManagedSite(1L, "my-slug", "wp_my-slug");
        site.setName("マイサイト");
        site.setBaseUrl("https://example.com");
        agentPlugins("Akismet");
        world.aiReplies("前置き\n```text\n生成された本文\n```\n");
        when(staticContentRepository.findBySiteIdAndContentType(1L, StaticContentType.PRIVACY_POLICY))
                .thenReturn(Optional.empty());
        when(staticContentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        StaticContentResponse response = service().generate(1L, StaticContentType.PRIVACY_POLICY);

        assertEquals("生成された本文", response.body());
        assertEquals("my-slug", world.bodiesOf("POST", PLUGINS).get(0).get("slug").asText());
        String prompt = sentPrompt();
        assertTrue(prompt.contains("マイサイト"));
        assertTrue(prompt.contains("https://example.com"));
        assertTrue(prompt.contains("Akismet"));
        assertTrue(prompt.contains("プライバシーポリシー"));
        assertTrue(!prompt.contains("休止中"), "有効でないプラグインはプロンプトに含めない");
    }

    @Test
    void generate_LLM応答が空なら例外() {
        world.addManagedSite(1L, "my-slug", "wp_my-slug");
        agentPlugins();
        world.aiReplies("   ");

        assertThrows(AiServiceGenerationException.class,
                () -> service().generate(1L, StaticContentType.OPERATOR_INFO));
        assertTrue(sentPrompt().contains("(有効化されたプラグインはありません)"));
        assertTrue(sentPrompt().contains("運営者情報"));
    }

    @Test
    void generate_サイトが存在しなければNotFound() {
        assertThrows(SiteNotFoundException.class, () -> service().generate(1L, StaticContentType.PRIVACY_POLICY));
    }

    @Test
    void generate_非managedかつSSH未設定なら例外() {
        world.addSite(1L, "AGENT接続");

        assertThrows(AiServiceGenerationException.class,
                () -> service().generate(1L, StaticContentType.PRIVACY_POLICY));
        assertTrue(world.http.requests("POST", "/api/internal/ai/generate").isEmpty());
    }

    @Test
    void generate_非managedのSSHサイトはブリッジ経由で有効なプラグインを取得する() {
        world.addSshSite(1L, "SSHサイト");
        world.respond("POST", CMS_PLUGINS, List.of("Contact Form 7", "Jetpack"));
        world.aiReplies("```text\n規約の本文\n```");
        when(staticContentRepository.findBySiteIdAndContentType(1L, StaticContentType.TERMS_OF_SERVICE))
                .thenReturn(Optional.empty());
        when(staticContentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        StaticContentResponse response = service().generate(1L, StaticContentType.TERMS_OF_SERVICE);

        assertEquals("規約の本文", response.body());
        assertEquals("ssh.example.com",
                world.bodiesOf("POST", CMS_PLUGINS).get(0).get("credentials").get("sshHost").asText());
        assertTrue(sentPrompt().contains("Contact Form 7、Jetpack"));
        assertTrue(sentPrompt().contains("利用規約"));
    }

    @Test
    void listBySite_一覧を返す() {
        StaticContent entity = new StaticContent();
        entity.setId(1L);
        entity.setSiteId(1L);
        entity.setContentType(StaticContentType.PRIVACY_POLICY);
        entity.setBody("本文");
        when(staticContentRepository.findBySiteId(1L)).thenReturn(List.of(entity));

        List<StaticContentResponse> result = service().listBySite(1L);

        assertEquals(1, result.size());
    }

    // ---- issue #1409: 保存せずに本文だけ生成する / 「保存」でstatic_contentへ書く ----

    private void managedSite() {
        Site site = world.addManagedSite(1L, "my-slug", "wp_my-slug");
        site.setName("マイサイト");
        site.setBaseUrl("https://example.com");
    }

    @Test
    void generateBody_本文だけを返しstatic_contentへは書かない() {
        managedSite();
        agentPlugins("Akismet");
        world.aiReplies("```text\n生成された本文\n```");

        String body = service().generateBody(1L, StaticContentType.TERMS_OF_SERVICE);

        assertEquals("生成された本文", body);
        verify(staticContentRepository, never()).save(any());
    }

    @Test
    void generateBody_text以外の応答はそのまま前後の空白を除いて返す() {
        managedSite();
        agentPlugins("Akismet");
        world.aiReplies("  素の本文\n");

        assertEquals("素の本文", service().generateBody(1L, StaticContentType.PRIVACY_POLICY));
    }

    @Test
    void generateBody_LLM応答が空なら例外() {
        managedSite();
        agentPlugins();
        world.aiReplies("  ");

        assertThrows(AiServiceGenerationException.class,
                () -> service().generateBody(1L, StaticContentType.PRIVACY_POLICY));
    }

    @Test
    void generateBody_サイトが存在しなければNotFound() {
        assertThrows(SiteNotFoundException.class,
                () -> service().generateBody(1L, StaticContentType.PRIVACY_POLICY));
    }

    @Test
    void requireSite_存在すれば何も起きず存在しなければNotFound() {
        when(world.siteRepository.existsById(1L)).thenReturn(true);
        when(world.siteRepository.existsById(2L)).thenReturn(false);

        service().requireSite(1L);
        assertThrows(SiteNotFoundException.class, () -> service().requireSite(2L));
    }

    @Test
    void save_既存が無ければ新規にstatic_contentへ保存する() {
        when(world.siteRepository.existsById(1L)).thenReturn(true);
        when(staticContentRepository.findBySiteIdAndContentType(1L, StaticContentType.PRIVACY_POLICY))
                .thenReturn(Optional.empty());
        when(staticContentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        StaticContentResponse response = service().save(1L, StaticContentType.PRIVACY_POLICY, "保存する本文");

        org.mockito.ArgumentCaptor<StaticContent> saved = org.mockito.ArgumentCaptor.forClass(StaticContent.class);
        verify(staticContentRepository).save(saved.capture());
        assertEquals(1L, saved.getValue().getSiteId());
        assertEquals(StaticContentType.PRIVACY_POLICY, saved.getValue().getContentType());
        assertEquals("保存する本文", response.body());
    }

    @Test
    void save_同じ種別が既にあれば上書きする() {
        StaticContent existing = new StaticContent();
        existing.setId(9L);
        existing.setSiteId(1L);
        existing.setContentType(StaticContentType.OPERATOR_INFO);
        existing.setBody("古い本文");
        when(world.siteRepository.existsById(1L)).thenReturn(true);
        when(staticContentRepository.findBySiteIdAndContentType(1L, StaticContentType.OPERATOR_INFO))
                .thenReturn(Optional.of(existing));
        when(staticContentRepository.save(existing)).thenReturn(existing);

        StaticContentResponse response = service().save(1L, StaticContentType.OPERATOR_INFO, "新しい本文");

        assertEquals("新しい本文", existing.getBody());
        assertEquals(9L, response.id());
    }

    @Test
    void save_サイトが存在しなければ保存せずNotFound() {
        when(world.siteRepository.existsById(1L)).thenReturn(false);

        assertThrows(SiteNotFoundException.class,
                () -> service().save(1L, StaticContentType.PRIVACY_POLICY, "本文"));
        verify(staticContentRepository, never()).save(any());
    }
}
