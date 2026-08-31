package com.letsblog.api.service;

import com.letsblog.api.ai.LlmClient;
import com.letsblog.api.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.api.cms.ssh.WordPressSshOperations;
import com.letsblog.api.domain.Site;
import com.letsblog.api.domain.StaticContent;
import com.letsblog.api.domain.StaticContentType;
import com.letsblog.api.dto.StaticContentResponse;
import com.letsblog.api.provisioning.WordPressBulkManagementClient;
import com.letsblog.api.repository.SiteRepository;
import com.letsblog.api.repository.StaticContentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaticContentGenerationServiceTest {

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private SiteService siteService;

    @Mock
    private WordPressBulkManagementClient bulkManagementClient;

    @Mock
    private WordPressSshOperations sshOperations;

    @Mock
    private LlmClient llmClient;

    @Mock
    private StaticContentRepository staticContentRepository;

    @InjectMocks
    private StaticContentGenerationService staticContentGenerationService;

    private Site managedSite() {
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("managed-site");
        site.setName("マネージドサイト");
        site.setBaseUrl("https://managed.example.com");
        site.setManagedWordpress(true);
        site.setWpSlug("managed-slug");
        return site;
    }

    private Site sshSite() {
        Site site = new Site();
        site.setId(2L);
        site.setSiteKey("ssh-site");
        site.setName("SSHサイト");
        site.setBaseUrl("https://ssh.example.com");
        site.setManagedWordpress(false);
        return site;
    }

    @Test
    void generate_managedSite_usesAgentPluginsAndPersists() {
        Site site = managedSite();
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(bulkManagementClient.listPlugins("managed-slug")).thenReturn(List.of(
                new WordPressBulkManagementClient.PluginThemeInfo("akismet", "active"),
                new WordPressBulkManagementClient.PluginThemeInfo("hello-dolly", "inactive")
        ));
        when(llmClient.generate(anyString())).thenReturn("```text\nプライバシーポリシー本文\n```");
        when(staticContentRepository.findBySiteIdAndContentType(1L, StaticContentType.PRIVACY_POLICY))
                .thenReturn(Optional.empty());
        when(staticContentRepository.save(any(StaticContent.class))).thenAnswer(invocation -> {
            StaticContent saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });

        StaticContentResponse response = staticContentGenerationService.generate(1L, StaticContentType.PRIVACY_POLICY);

        assertEquals(10L, response.id());
        assertEquals("プライバシーポリシー本文", response.body());
        assertEquals(StaticContentType.PRIVACY_POLICY, response.contentType());

        var promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(llmClient).generate(promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("akismet"));
        assertFalse(promptCaptor.getValue().contains("hello-dolly"));
    }

    @Test
    void generate_termsOfService_persistsGeneratedBody() {
        Site site = managedSite();
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(bulkManagementClient.listPlugins("managed-slug")).thenReturn(List.of());
        when(llmClient.generate(anyString())).thenReturn("```text\n利用規約本文\n```");
        when(staticContentRepository.findBySiteIdAndContentType(1L, StaticContentType.TERMS_OF_SERVICE))
                .thenReturn(Optional.empty());
        when(staticContentRepository.save(any(StaticContent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StaticContentResponse response = staticContentGenerationService.generate(1L, StaticContentType.TERMS_OF_SERVICE);

        assertEquals("利用規約本文", response.body());
        assertEquals(StaticContentType.TERMS_OF_SERVICE, response.contentType());
    }

    @Test
    void generate_sshSite_usesSshPlugins() {
        Site site = sshSite();
        when(siteRepository.findById(2L)).thenReturn(Optional.of(site));

        WordPressCredentials sshCreds = new WordPressCredentials(
                "https://ssh.example.com", null, "SSH", "host", 22, "user", "/var/www/html",
                "pem", "fingerprint", null);
        SiteService.SiteDataSource dataSource = new SiteService.SiteDataSource(false, sshCreds);
        when(siteService.resolveDataSource(site)).thenReturn(dataSource);
        when(sshOperations.listPlugins(sshCreds)).thenReturn(List.of(
                new WordPressSshOperations.PluginThemeInfo("contact-form-7", "active")
        ));
        when(llmClient.generate(anyString())).thenReturn("```text\n運営者情報本文\n```");
        when(staticContentRepository.findBySiteIdAndContentType(2L, StaticContentType.OPERATOR_INFO))
                .thenReturn(Optional.empty());
        when(staticContentRepository.save(any(StaticContent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StaticContentResponse response = staticContentGenerationService.generate(2L, StaticContentType.OPERATOR_INFO);

        assertEquals("運営者情報本文", response.body());
    }

    @Test
    void generate_updatesExistingEntryInsteadOfCreatingDuplicate() {
        Site site = managedSite();
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(bulkManagementClient.listPlugins("managed-slug")).thenReturn(List.of());
        when(llmClient.generate(anyString())).thenReturn("```text\n更新後の本文\n```");

        StaticContent existing = new StaticContent();
        existing.setId(5L);
        existing.setSiteId(1L);
        existing.setContentType(StaticContentType.PRIVACY_POLICY);
        existing.setBody("古い本文");
        when(staticContentRepository.findBySiteIdAndContentType(1L, StaticContentType.PRIVACY_POLICY))
                .thenReturn(Optional.of(existing));
        when(staticContentRepository.save(any(StaticContent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StaticContentResponse response = staticContentGenerationService.generate(1L, StaticContentType.PRIVACY_POLICY);

        assertEquals(5L, response.id());
        assertEquals("更新後の本文", response.body());
    }

    @Test
    void generate_blankLlmResponse_throwsAiServiceGenerationException() {
        Site site = managedSite();
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(bulkManagementClient.listPlugins("managed-slug")).thenReturn(List.of());
        when(llmClient.generate(anyString())).thenReturn("   ");

        AiServiceGenerationException exception = assertThrows(
                AiServiceGenerationException.class,
                () -> staticContentGenerationService.generate(1L, StaticContentType.PRIVACY_POLICY));

        assertTrue(exception.getMessage().contains("空でした"));
        verify(staticContentRepository, never()).save(any());
    }

    @Test
    void generate_llmResponseWithoutFence_usesFullResponseAsBody() {
        // 一部のローカルLLM(Ollama等)は```text ... ```フェンスの指示に従わないことがあるため、
        // フェンスが無くてもレスポンス全体を本文として使えることを確認する。
        Site site = managedSite();
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(bulkManagementClient.listPlugins("managed-slug")).thenReturn(List.of());
        when(llmClient.generate(anyString())).thenReturn("フェンス無しの本文です。");
        when(staticContentRepository.findBySiteIdAndContentType(1L, StaticContentType.PRIVACY_POLICY))
                .thenReturn(Optional.empty());
        when(staticContentRepository.save(any(StaticContent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StaticContentResponse response = staticContentGenerationService.generate(1L, StaticContentType.PRIVACY_POLICY);

        assertEquals("フェンス無しの本文です。", response.body());
    }

    @Test
    void generate_siteNotFound_throwsSiteNotFoundException() {
        when(siteRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(SiteNotFoundException.class,
                () -> staticContentGenerationService.generate(99L, StaticContentType.PRIVACY_POLICY));
    }

    @Test
    void generate_noAvailableDataSource_throwsAiServiceGenerationException() {
        Site site = sshSite();
        when(siteRepository.findById(2L)).thenReturn(Optional.of(site));
        SiteService.SiteDataSource unavailable = new SiteService.SiteDataSource(false, null);
        when(siteService.resolveDataSource(site)).thenReturn(unavailable);

        AiServiceGenerationException exception = assertThrows(
                AiServiceGenerationException.class,
                () -> staticContentGenerationService.generate(2L, StaticContentType.PRIVACY_POLICY));

        assertTrue(exception.getMessage().contains("プラグイン情報を取得できません"));
    }
}
