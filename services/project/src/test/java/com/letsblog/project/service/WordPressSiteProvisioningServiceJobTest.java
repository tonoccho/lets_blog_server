package com.letsblog.project.service;

import com.letsblog.project.aop.AuditLog;
import com.letsblog.project.domain.AuditLogAction;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.testsupport.FakeHttpServer;
import com.letsblog.project.testsupport.ProjectWorld;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * サイト自動構築のジョブ用の実行本体(issue #1479)。同期の{@code createManagedSite}は
 * {@code @Transactional}がリモートI/O全体を包むが、ジョブ用はバックグラウンドスレッドが
 * DB接続を数分掴まないよう、メソッド全体をトランザクションで包まない(#1122〜#1124)。
 * そのため、同期経路ではトランザクションのロールバックが担っていた後始末を明示的に行う。
 *
 * <p>issue #1817: SiteService・各クライアントは実物で、DB(SiteRepository)とHTTPの向こう(provision-agent・
 * publishing-service)だけを差し替える。
 */
@DisplayName("WordPressSiteProvisioningService: ジョブ用の構築(issue #1479)")
class WordPressSiteProvisioningServiceJobTest {

    private final ProjectWorld world = new ProjectWorld();
    private WordPressSiteProvisioningService service;
    private final List<String> phases = new ArrayList<>();
    private final Consumer<String> listener = phases::add;

    @BeforeEach
    void setUp() {
        service = new WordPressSiteProvisioningService(world.wordPressProvisioningClient, world.wordPressSyncClient,
                world.siteService, world.siteRepository, world.domainEventPublisher);
        world.registrationSucceeds();
        world.respond("POST", "/provision", Map.of("url", "https://localhost/sites/my-site", "adminUser", "admin"));
        world.http.on("POST", "/deprovision", FakeHttpServer.Response.empty(200));
        world.http.on("POST", "/sync", FakeHttpServer.Response.empty(200));
    }

    @AfterEach
    void close() {
        world.close();
    }

    private static CreateManagedWordPressSiteRequest request(Long templateSiteId, String locale) {
        return new CreateManagedWordPressSiteRequest(
                "Name", "my-site", "Title", "admin", "admin@example.com", "password", locale, templateSiteId);
    }

    private Site site() {
        Site site = new Site();
        site.setId(7L);
        site.setName("Name");
        site.setSiteKey("my-site");
        site.setCreatedAt(LocalDateTime.of(2026, 9, 8, 20, 3, 35));
        site.setUpdatedAt(LocalDateTime.of(2026, 9, 9, 1, 2, 3));
        when(world.siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(site));
        return site;
    }

    private int deprovisions() {
        return world.http.requests("POST", "/deprovision").size();
    }

    @Test
    @DisplayName("成功すると進行段階を provisioning → registering の順に通知し、サイトを返す")
    void success() {
        Site site = site();

        SiteResponse response = service.createManagedSiteForJob(request(null, null), listener);

        assertEquals(7L, response.id());
        assertEquals(List.of("provisioning", "registering"), phases);
        assertTrue(site.isManagedWordpress());
        assertEquals("my-site", site.getWpSlug());
        assertEquals("wp_my-site", site.getWpDbName());
        verify(world.siteRepository).save(site);
    }

    @Test
    @DisplayName("siteKey が重複していれば、何も構築せず DuplicateSiteKeyException にする")
    void duplicateSiteKey() {
        when(world.siteRepository.existsBySiteKey("my-site")).thenReturn(true);

        DuplicateSiteKeyException e = assertThrows(DuplicateSiteKeyException.class,
                () -> service.createManagedSiteForJob(request(null, "ja"), listener));

        assertTrue(e.getMessage().contains("my-site"));
        assertTrue(phases.isEmpty());
        assertTrue(world.http.requests("POST", "/provision").isEmpty());
        assertEquals(0, deprovisions());
    }

    @Test
    @DisplayName("構築(ProvisioningException)に失敗すれば deprovision で実体を消して再スローする")
    void provisioningFailureRollsBack() {
        world.fail("POST", "/provision", 500);

        assertThrows(ProvisioningException.class,
                () -> service.createManagedSiteForJob(request(null, "ja"), listener));

        assertEquals(1, deprovisions());
        assertEquals(List.of("provisioning"), phases);
    }

    @Test
    @DisplayName("実体が既にあった(409)場合は、既存の実体を消さない")
    void alreadyProvisionedKeepsExistingEntity() {
        world.fail("POST", "/provision", 409);

        assertThrows(SiteAlreadyProvisionedException.class,
                () -> service.createManagedSiteForJob(request(null, "ja"), listener));

        assertEquals(0, deprovisions());
    }

    @Test
    @DisplayName("登録に失敗すれば deprovision する(登録自体はトランザクションごと戻る)")
    void registerFailureRollsBack() {
        world.fail("POST", "/api/internal/project/cms/provision", 500);
        when(world.siteRepository.findBySiteKey("my-site")).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class,
                () -> service.createManagedSiteForJob(request(null, "ja"), listener));

        assertEquals(1, deprovisions());
        verify(world.siteRepository, never()).delete(org.mockito.ArgumentMatchers.any());
        assertEquals(List.of("provisioning", "registering"), phases);
    }

    @Test
    @DisplayName("登録後の保存に失敗すれば、登録済みのサイトレコードも消して deprovision する")
    void postRegisterFailureRemovesSiteRecord() {
        Site site = site();
        when(world.siteRepository.save(site)).thenThrow(new IllegalStateException("保存失敗"));

        assertThrows(IllegalStateException.class,
                () -> service.createManagedSiteForJob(request(null, "ja"), listener));

        assertEquals(1, deprovisions());
        verify(world.siteRepository).delete(site);
    }

    @Test
    @DisplayName("テンプレート指定時は複製し、複製に失敗した場合の後始末は複製側が1度だけ行う")
    void templateCloneFailureCleansUpOnce() {
        Site site = site();
        world.addManagedSite(3L, "tpl", "wp_tpl");
        world.fail("POST", "/sync", 500);

        assertThrows(ProvisioningException.class,
                () -> service.createManagedSiteForJob(request(3L, "ja"), listener));

        assertEquals(1, deprovisions());
        verify(world.siteRepository).delete(site);
    }

    @Test
    @DisplayName("テンプレート指定時は複製元のスラッグを agent へ渡す")
    void templateClone() {
        site();
        world.addManagedSite(3L, "tpl", "wp_tpl");

        service.createManagedSiteForJob(request(3L, "ja"), listener);

        assertEquals("tpl", world.bodiesOf("POST", "/sync").get(0).get("fromSlug").asText());
    }

    @Test
    @DisplayName("テンプレートが自動構築サイトでなければ、複製側が後始末をして IllegalArgumentException にする")
    void nonManagedTemplate() {
        Site site = site();
        world.addSite(3L, "テンプレート");

        assertThrows(IllegalArgumentException.class,
                () -> service.createManagedSiteForJob(request(3L, "ja"), listener));

        assertEquals(1, deprovisions());
        verify(world.siteRepository).delete(site);
    }

    @Test
    @DisplayName("テンプレートサイトが存在しなければ、構築を始める前に SiteNotFoundException にし何も残さない")
    void missingTemplateIsRejectedBeforeProvisioning() {
        assertThrows(SiteNotFoundException.class,
                () -> service.createManagedSiteForJob(request(3L, "ja"), listener));

        assertTrue(phases.isEmpty());
        assertTrue(world.http.requests("POST", "/provision").isEmpty());
        assertEquals(0, deprovisions());
        assertTrue(world.http.requests("POST", "/api/internal/project/cms/provision").isEmpty());
    }

    @Test
    @DisplayName("リスナーが null でも構築できる")
    void nullListener() {
        site();

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
