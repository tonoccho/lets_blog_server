package com.letsblog.project.service;

import com.letsblog.project.client.AiGenerationClient;
import com.letsblog.project.client.CmsProvisioningBridgeClient;
import com.letsblog.project.domain.Site;
import com.letsblog.project.domain.StaticContent;
import com.letsblog.project.domain.StaticContentType;
import com.letsblog.project.dto.StaticContentResponse;
import com.letsblog.project.provisioning.WordPressAgentPluginsClient;
import com.letsblog.project.repository.SiteRepository;
import com.letsblog.project.repository.StaticContentRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** StaticContentGenerationServiceの回帰テスト(issue #577 stage2、legacy-apiから移設)。 */
@ExtendWith(MockitoExtension.class)
class StaticContentGenerationServiceTest {

    @Mock
    private SiteRepository siteRepository;
    @Mock
    private SiteService siteService;
    @Mock
    private WordPressAgentPluginsClient agentPluginsClient;
    @Mock
    private CmsProvisioningBridgeClient bridgeClient;
    @Mock
    private AiGenerationClient aiGenerationClient;
    @Mock
    private StaticContentRepository staticContentRepository;

    private StaticContentGenerationService service() {
        return new StaticContentGenerationService(
                siteRepository, siteService, agentPluginsClient, bridgeClient, aiGenerationClient,
                staticContentRepository);
    }

    @Test
    void generate_managedサイトはエージェント経由でプラグイン取得しLLM応答をtextブロックから抽出して保存する() {
        Site site = new Site();
        site.setId(1L);
        site.setName("マイサイト");
        site.setBaseUrl("https://example.com");
        site.setManagedWordpress(true);
        site.setWpSlug("my-slug");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(agentPluginsClient.listActivePluginNames("my-slug")).thenReturn(List.of("Akismet"));
        when(aiGenerationClient.generate(any(), any(), any()))
                .thenReturn("前置き\n```text\n生成された本文\n```\n");
        when(staticContentRepository.findBySiteIdAndContentType(1L, StaticContentType.PRIVACY_POLICY))
                .thenReturn(Optional.empty());
        when(staticContentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        StaticContentResponse response = service().generate(1L, StaticContentType.PRIVACY_POLICY);

        assertEquals("生成された本文", response.body());
    }

    @Test
    void generate_LLM応答が空なら例外() {
        Site site = new Site();
        site.setId(1L);
        site.setManagedWordpress(true);
        site.setWpSlug("my-slug");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(agentPluginsClient.listActivePluginNames("my-slug")).thenReturn(List.of());
        when(aiGenerationClient.generate(any(), any(), any())).thenReturn("   ");

        assertThrows(AiServiceGenerationException.class,
                () -> service().generate(1L, StaticContentType.OPERATOR_INFO));
    }

    @Test
    void generate_サイトが存在しなければNotFound() {
        when(siteRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(SiteNotFoundException.class, () -> service().generate(1L, StaticContentType.PRIVACY_POLICY));
    }

    @Test
    void generate_非managedかつSSH未設定なら例外() {
        Site site = new Site();
        site.setId(1L);
        site.setManagedWordpress(false);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(siteService.resolveDataSource(site)).thenReturn(new SiteService.SiteDataSource(false, null));

        assertThrows(AiServiceGenerationException.class,
                () -> service().generate(1L, StaticContentType.PRIVACY_POLICY));
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
}
