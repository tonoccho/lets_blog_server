package com.letsblog.project.service;

import com.letsblog.project.aop.AuditLog;
import com.letsblog.project.domain.AuditLogAction;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.messaging.DomainEventPublisher;
import com.letsblog.project.provisioning.WordPressProvisioningClient;
import com.letsblog.project.provisioning.WordPressSyncClient;
import com.letsblog.project.repository.SiteRepository;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * サイト自動構築のジョブ用の実行本体(issue #1479)。同期の{@code createManagedSite}は
 * {@code @Transactional}がリモートI/O全体を包むが、ジョブ用はバックグラウンドスレッドが
 * DB接続を数分掴まないよう、メソッド全体をトランザクションで包まない(#1122〜#1124)。
 * そのため、同期経路ではトランザクションのロールバックが担っていた後始末を明示的に行う。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WordPressSiteProvisioningService: ジョブ用の構築(issue #1479)")
class WordPressSiteProvisioningServiceJobTest {

    @Mock
    private WordPressProvisioningClient provisioningClient;
    @Mock
    private WordPressSyncClient syncClient;
    @Mock
    private SiteService siteService;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private DomainEventPublisher domainEventPublisher;

    private WordPressSiteProvisioningService service;
    private final List<String> phases = new ArrayList<>();
    private final Consumer<String> listener = phases::add;

    @BeforeEach
    void setUp() {
        service = new WordPressSiteProvisioningService(
                provisioningClient, syncClient, siteService, siteRepository, domainEventPublisher);
    }

    private static CreateManagedWordPressSiteRequest request(Long templateSiteId, String locale) {
        return new CreateManagedWordPressSiteRequest(
                "Name", "my-site", "Title", "admin", "admin@example.com", "password", locale, templateSiteId);
    }

    private static Site site() {
        Site site = new Site();
        site.setId(7L);
        site.setName("Name");
        site.setSiteKey("my-site");
        site.setCreatedAt(LocalDateTime.of(2026, 9, 8, 20, 3, 35));
        site.setUpdatedAt(LocalDateTime.of(2026, 9, 9, 1, 2, 3));
        return site;
    }

    private void provisionSucceeds() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(false);
        when(provisioningClient.provision(any())).thenReturn(
                new WordPressProvisioningClient.ProvisionResult("https://localhost/sites/my-site", "admin"));
    }

    private void registerSucceeds() {
        when(siteService.register(any())).thenReturn(new SiteResponse(
                7L, "Name", "my-site", null, null, Instant.now(), Instant.now(), "SUCCESS", false, false, null));
    }

    @Test
    @DisplayName("成功すると進行段階を provisioning → registering の順に通知し、サイトを返す")
    void success() {
        provisionSucceeds();
        registerSucceeds();
        Site site = site();
        when(siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(site));

        SiteResponse response = service.createManagedSiteForJob(request(null, null), listener);

        assertEquals(7L, response.id());
        assertEquals(List.of("provisioning", "registering"), phases);
        assertTrue(site.isManagedWordpress());
        assertEquals("my-site", site.getWpSlug());
        assertEquals("wp_my-site", site.getWpDbName());
        verify(siteRepository).save(site);
    }

    @Test
    @DisplayName("siteKey が重複していれば、何も構築せず DuplicateSiteKeyException にする")
    void duplicateSiteKey() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(true);

        DuplicateSiteKeyException e = assertThrows(DuplicateSiteKeyException.class,
                () -> service.createManagedSiteForJob(request(null, "ja"), listener));

        assertTrue(e.getMessage().contains("my-site"));
        assertTrue(phases.isEmpty());
        verify(provisioningClient, never()).provision(any());
        verify(provisioningClient, never()).deprovision(any(), any());
    }

    @Test
    @DisplayName("構築(ProvisioningException)に失敗すれば deprovision で実体を消して再スローする")
    void provisioningFailureRollsBack() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(false);
        when(provisioningClient.provision(any())).thenThrow(new ProvisioningException("失敗", null));

        assertThrows(ProvisioningException.class,
                () -> service.createManagedSiteForJob(request(null, "ja"), listener));

        verify(provisioningClient).deprovision("my-site", "wp_my-site");
        assertEquals(List.of("provisioning"), phases);
    }

    @Test
    @DisplayName("実体が既にあった(409)場合は、既存の実体を消さない")
    void alreadyProvisionedKeepsExistingEntity() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(false);
        when(provisioningClient.provision(any())).thenThrow(new SiteAlreadyProvisionedException("既にある", null));

        assertThrows(SiteAlreadyProvisionedException.class,
                () -> service.createManagedSiteForJob(request(null, "ja"), listener));

        verify(provisioningClient, never()).deprovision(any(), any());
    }

    @Test
    @DisplayName("登録に失敗すれば deprovision する(登録自体はトランザクションごと戻る)")
    void registerFailureRollsBack() {
        provisionSucceeds();
        when(siteService.register(any())).thenThrow(new IllegalStateException("登録失敗"));
        when(siteRepository.findBySiteKey("my-site")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> service.createManagedSiteForJob(request(null, "ja"), listener));

        verify(provisioningClient).deprovision("my-site", "wp_my-site");
        verify(siteRepository, never()).delete(any());
        assertEquals(List.of("provisioning", "registering"), phases);
    }

    @Test
    @DisplayName("登録後の保存に失敗すれば、登録済みのサイトレコードも消して deprovision する")
    void postRegisterFailureRemovesSiteRecord() {
        provisionSucceeds();
        registerSucceeds();
        Site site = site();
        when(siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(site));
        when(siteRepository.save(site)).thenThrow(new IllegalStateException("保存失敗"));

        assertThrows(IllegalStateException.class,
                () -> service.createManagedSiteForJob(request(null, "ja"), listener));

        verify(provisioningClient).deprovision("my-site", "wp_my-site");
        verify(siteRepository).delete(site);
    }

    @Test
    @DisplayName("テンプレート指定時は複製し、複製に失敗した場合の後始末は複製側が1度だけ行う")
    void templateCloneFailureCleansUpOnce() {
        provisionSucceeds();
        registerSucceeds();
        Site site = site();
        when(siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(site));
        Site template = new Site();
        template.setManagedWordpress(true);
        template.setWpSlug("tpl");
        template.setWpDbName("wp_tpl");
        when(siteRepository.findById(3L)).thenReturn(Optional.of(template));
        org.mockito.Mockito.doThrow(new ProvisioningException("同期失敗", null)).when(syncClient).sync(any());

        assertThrows(ProvisioningException.class,
                () -> service.createManagedSiteForJob(request(3L, "ja"), listener));

        verify(provisioningClient, org.mockito.Mockito.times(1)).deprovision("my-site", "wp_my-site");
        verify(siteRepository).delete(site);
    }

    @Test
    @DisplayName("テンプレートサイトが存在しなければ、構築を始める前に SiteNotFoundException にし何も残さない")
    void missingTemplateIsRejectedBeforeProvisioning() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(false);
        when(siteRepository.findById(3L)).thenReturn(Optional.empty());

        assertThrows(SiteNotFoundException.class,
                () -> service.createManagedSiteForJob(request(3L, "ja"), listener));

        assertTrue(phases.isEmpty());
        verify(provisioningClient, never()).provision(any());
        verify(provisioningClient, never()).deprovision(any(), any());
        verify(siteService, never()).register(any());
    }

    @Test
    @DisplayName("リスナーが null でも構築できる")
    void nullListener() {
        provisionSucceeds();
        registerSucceeds();
        when(siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(site()));

        assertNotNull(service.createManagedSiteForJob(request(null, " "), null));
    }

    @Test
    @DisplayName("監査ログは WORDPRESS_PROVISIONED のまま、メソッド全体は @Transactional で包まない")
    void auditedButNotTransactional() throws NoSuchMethodException {
        Method method = WordPressSiteProvisioningService.class.getMethod(
                "createManagedSiteForJob", CreateManagedWordPressSiteRequest.class, Consumer.class);

        assertEquals(AuditLogAction.WORDPRESS_PROVISIONED, method.getAnnotation(AuditLog.class).action());
        assertNull(method.getAnnotation(Transactional.class));
        assertNull(WordPressSiteProvisioningService.class.getAnnotation(Transactional.class));
    }

    @Test
    @DisplayName("DuplicateSiteKeyException は既存の IllegalArgumentException として扱える")
    void duplicateIsIllegalArgument() {
        assertTrue(IllegalArgumentException.class.isAssignableFrom(DuplicateSiteKeyException.class));
    }
}
