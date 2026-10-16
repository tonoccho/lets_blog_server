package com.letsblog.project.testsupport;

import com.letsblog.project.client.XApiClient;
import com.letsblog.project.cms.LetsblogPluginStatus;
import com.letsblog.project.domain.Project;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.service.SnsXService;
import com.letsblog.project.service.XAuthorizationStore;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.mockito.Mockito;

/**
 * SNS連携サービス(X・Threads・LinkedIn・Facebook・はてな)のテストが共有する土台(issue #1817)。
 * 自前のクラスはすべて実物(本番サイト・letsblogプラグインの状態はCMSブリッジのフェイクHTTPが答える)で、
 * 差し替えるのはDBのリポジトリ({@link ProjectRepository})だけ。
 */
public final class SnsFixture implements AutoCloseable {

    public final ProjectWorld world = new ProjectWorld();
    public final ProjectRepository projectRepository = Mockito.mock(ProjectRepository.class);
    public final XAuthorizationStore store = new XAuthorizationStore();
    public final SnsXService snsXService = new SnsXService(projectRepository, world.siteRepository, world.siteService,
            new XApiClient(world.http.baseUrl(), "https://x.example/authorize"), store, world.currentActorService,
            world.objectMapper);

    /** プロジェクト7の本番サイトを指定する(nullなら本番サイト無し)。 */
    public void projectWithProductionSite(Long siteId) {
        Project project = new Project();
        project.setId(7L);
        project.setProductionSiteId(siteId);
        Mockito.when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
    }

    /** プロジェクト7の本番サイト3が、letsblogプラグイン導入済みで接続できる状態にする。 */
    public void connectableSite() {
        projectWithProductionSite(3L);
        world.addSite(3L, "本番サイト");
        world.pluginState(LetsblogPluginStatus.State.INSTALLED);
    }

    /** 認可画面のURLのクエリから、指定のパラメーターを取り出す。 */
    public static String queryParam(String url, String name) {
        Map<String, String> query = Stream.of(URI.create(url).getRawQuery().split("&"))
                .map(kv -> kv.split("=", 2))
                .collect(Collectors.toMap(kv -> kv[0], kv -> URLDecoder.decode(kv[1], StandardCharsets.UTF_8)));
        return query.get(name);
    }

    @Override
    public void close() {
        world.close();
    }
}
