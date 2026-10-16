package com.letsblog.project.testsupport;

import com.letsblog.project.crypto.SshKeyGenerationService;
import com.letsblog.project.repository.ProjectPvRuleRepository;
import com.letsblog.project.repository.ProjectPvSyncStateRepository;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.repository.ProjectSnsTemplateRepository;
import com.letsblog.project.repository.StaticContentRepository;
import com.letsblog.project.repository.TagDesignSettingRepository;
import com.letsblog.project.service.JobHeartbeatTracker;
import com.letsblog.project.service.LetsblogSyncService;
import com.letsblog.project.service.ManagedSiteProvisioningJobRunner;
import com.letsblog.project.service.ManagedSiteProvisioningJobStarter;
import com.letsblog.project.service.ProjectEnvironmentSyncJobRunner;
import com.letsblog.project.service.ProjectEnvironmentSyncJobStarter;
import com.letsblog.project.service.ProjectEnvironmentSyncService;
import com.letsblog.project.service.ProjectService;
import com.letsblog.project.service.PvRuleService;
import com.letsblog.project.service.SnsTemplateService;
import com.letsblog.project.service.SnsXService;
import com.letsblog.project.service.XAuthorizationStore;
import com.letsblog.project.client.XApiClient;
import com.letsblog.project.service.SshKeyPairService;
import com.letsblog.project.service.StaticContentGenerationService;
import com.letsblog.project.service.TagDesignGenerationService;
import com.letsblog.project.service.TagDesignSettingService;
import com.letsblog.project.service.TextGenerationJobRunner;
import com.letsblog.project.service.TextGenerationJobStarter;
import com.letsblog.project.service.WordPressSiteProvisioningService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.mockito.Mockito;

/**
 * コントローラーのテストが使う「自前のクラスはすべて実物の世界」(issue #1818)。
 *
 * <p>{@link ProjectWorld}(HTTPのフェイクサーバー・MQの{@code RabbitTemplate}・実物のClient/CurrentActorService/
 * AdminAuthorizationService/SiteService)の上に、コントローラーが依存するサービスを実物で組み立てる。
 * 差し替えるのは外部との通信だけ: HTTPは{@link FakeHttpServer}、MQは{@code RabbitTemplate}、
 * DBはSpring Dataのリポジトリ(自前のコードを持たないインタフェース)。
 *
 * <p>{@code @Async}のジョブは、Springの実際のAOPプロキシ({@link AsyncProxies})で、
 * 呼び出しを{@link #jobs}へためるExecutorに載せる。{@link #drainJobs()}で実行する。
 */
public final class ProjectApp implements AutoCloseable {

    public final ProjectWorld world = new ProjectWorld();

    // DB(Spring Dataのリポジトリ)
    public final ProjectRepository projectRepository = Mockito.mock(ProjectRepository.class);
    public final TagDesignSettingRepository tagDesignSettingRepository = Mockito.mock(TagDesignSettingRepository.class);
    public final StaticContentRepository staticContentRepository = Mockito.mock(StaticContentRepository.class);
    public final ProjectPvRuleRepository pvRuleRepository = Mockito.mock(ProjectPvRuleRepository.class);
    public final ProjectPvSyncStateRepository pvSyncStateRepository = Mockito.mock(ProjectPvSyncStateRepository.class);
    public final ProjectSnsTemplateRepository snsTemplateRepository = Mockito.mock(ProjectSnsTemplateRepository.class);

    /** {@code @Async}のジョブと、letsblogプラグインへの同期をためる実行枠。 */
    public final List<Runnable> jobs = new ArrayList<>();
    private final Executor queueingExecutor = jobs::add;

    public final ProjectService projectService;
    public final TagDesignSettingService tagDesignSettingService;
    public final TagDesignGenerationService tagDesignGenerationService;
    public final LetsblogSyncService letsblogSyncService;
    public final ProjectEnvironmentSyncService projectEnvironmentSyncService;
    public final ProjectEnvironmentSyncJobStarter projectEnvironmentSyncJobStarter;
    public final WordPressSiteProvisioningService wordPressSiteProvisioningService;
    public final ManagedSiteProvisioningJobStarter managedSiteProvisioningJobStarter;
    public final StaticContentGenerationService staticContentGenerationService;
    public final TextGenerationJobStarter textGenerationJobStarter;
    public final SshKeyGenerationService sshKeyGenerationService = new SshKeyGenerationService();
    public final SshKeyPairService sshKeyPairService;
    public final PvRuleService pvRuleService;
    public final SnsXService snsXService;
    public final SnsTemplateService snsTemplateService;

    public ProjectApp() {
        projectService = new ProjectService(projectRepository, world.siteRepository, world.siteService,
                world.domainEventPublisher);
        tagDesignSettingService = new TagDesignSettingService(tagDesignSettingRepository);
        tagDesignGenerationService = new TagDesignGenerationService(world.aiGenerationClient);
        letsblogSyncService = new LetsblogSyncService(world.siteService, world.siteRepository, projectRepository,
                world.contentBridgeClient, world.currentActorService, queueingExecutor);
        projectEnvironmentSyncService = new ProjectEnvironmentSyncService(projectRepository, world.siteRepository,
                world.siteService, world.wordPressSyncClient, world.cmsBridgeClient, world.identityBridgeClient,
                world.currentActorService);
        JobHeartbeatTracker tracker = new JobHeartbeatTracker(world.generationJobClient, world.objectMapper);
        ProjectEnvironmentSyncJobRunner syncRunner = AsyncProxies.async(new ProjectEnvironmentSyncJobRunner(
                projectEnvironmentSyncService, world.currentActorService, world.generationJobClient,
                world.objectMapper, tracker), "environmentSyncExecutor", queueingExecutor);
        projectEnvironmentSyncJobStarter = new ProjectEnvironmentSyncJobStarter(
                world.generationJobClient, syncRunner, world.currentActorService, world.objectMapper, tracker);
        wordPressSiteProvisioningService = new WordPressSiteProvisioningService(world.wordPressProvisioningClient,
                world.wordPressSyncClient, world.siteService, world.siteRepository, world.domainEventPublisher);
        ManagedSiteProvisioningJobRunner provisioningRunner = AsyncProxies.async(new ManagedSiteProvisioningJobRunner(
                wordPressSiteProvisioningService, world.currentActorService, world.generationJobClient,
                world.objectMapper, tracker), "siteProvisioningExecutor", queueingExecutor);
        managedSiteProvisioningJobStarter = new ManagedSiteProvisioningJobStarter(
                world.generationJobClient, provisioningRunner, world.currentActorService, world.objectMapper, tracker);
        staticContentGenerationService = new StaticContentGenerationService(world.siteRepository, world.siteService,
                world.wordPressAgentPluginsClient, world.cmsBridgeClient, world.aiGenerationClient,
                staticContentRepository);
        TextGenerationJobRunner textRunner = AsyncProxies.async(new TextGenerationJobRunner(
                staticContentGenerationService, tagDesignGenerationService, tagDesignSettingService,
                world.generationJobClient, world.objectMapper), "textGenerationExecutor", queueingExecutor);
        textGenerationJobStarter = new TextGenerationJobStarter(world.generationJobClient, textRunner,
                staticContentGenerationService, world.currentActorService, world.objectMapper);
        sshKeyPairService = new SshKeyPairService(world.sshKeyPairRepository, sshKeyGenerationService,
                world.credentialCipher, world.adminAuthorizationService);
        pvRuleService = new PvRuleService(projectRepository, world.siteRepository, world.siteService,
                world.analyticsBridgeClient, pvRuleRepository, pvSyncStateRepository, world.currentActorService,
                world.objectMapper);
        snsXService = new SnsXService(projectRepository, world.siteRepository, world.siteService,
                new XApiClient(world.http.baseUrl(), "https://x.example/authorize"), new XAuthorizationStore(),
                world.currentActorService, world.objectMapper);
        snsTemplateService = new SnsTemplateService(projectRepository, world.siteRepository, world.siteService,
                snsTemplateRepository, world.objectMapper);
    }

    /**
     * プロジェクト{@code projectId}がローカルサイト{@code siteId}を持ち、そのサイトがletsblogプラグイン導入済みで、
     * content-serviceが同期内容を返し、プラグインがそのハッシュを保存する状態にする(同期が成功する状態)。
     */
    public com.letsblog.project.domain.Site syncableProject(Long projectId, Long siteId) {
        com.letsblog.project.domain.Project project = new com.letsblog.project.domain.Project();
        project.setId(projectId);
        project.setLocalSiteId(siteId);
        Mockito.when(projectRepository.findById(projectId)).thenReturn(java.util.Optional.of(project));
        Mockito.when(projectRepository.findAll()).thenReturn(List.of(project));
        com.letsblog.project.domain.Site site = world.addSite(siteId, "site-" + siteId);
        Mockito.when(world.siteRepository.save(Mockito.any(com.letsblog.project.domain.Site.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        world.pluginState(com.letsblog.project.cms.LetsblogPluginStatus.State.INSTALLED);
        world.respond("GET", "/api/internal/content/projects/\\d+/letsblog-sync-payload",
                new com.letsblog.project.client.ContentBridgeClient.SyncPayload("{\"cssBundle\":\".a{}\"}", "h1"));
        world.respond("POST", "/api/internal/project/cms/sync-letsblog-plugin",
                new com.letsblog.project.cms.LetsblogSyncResult("h1"));
        return site;
    }

    /** letsblogプラグインへ送られた同期の要求の数。 */
    public int pluginSyncRequests() {
        return world.http.requests("POST", "/api/internal/project/cms/sync-letsblog-plugin").size();
    }

    /** ためたジョブ(非同期の実行本体)を、呼び出しの順に実行する。 */
    public void drainJobs() {
        List<Runnable> copy = new ArrayList<>(jobs);
        jobs.clear();
        copy.forEach(Runnable::run);
    }

    @Override
    public void close() {
        world.close();
    }
}
