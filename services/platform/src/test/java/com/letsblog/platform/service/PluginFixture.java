package com.letsblog.platform.service;

import com.letsblog.common.net.ConnectionDestinationGuard;
import com.letsblog.common.net.InterfaceAddr;
import com.letsblog.platform.domain.PluginRegistration;
import com.letsblog.platform.domain.ProjectPluginRegistration;
import com.letsblog.platform.repository.PluginRegistrationRepository;
import com.letsblog.platform.repository.ProjectPluginRegistrationRepository;
import com.letsblog.platform.testsupport.InMemoryRepositories;
import com.letsblog.platform.testsupport.PlatformWorld;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * プラグイン登録のコントローラーのテストが使う、自前のクラスはすべて実物の世界(issue #1825)。
 * {@link PlatformWorld}(identity-serviceのHTTPフェイク)の上に、システム全体・プロジェクト単位の登録サービスを実物で組み立てる。
 * 差し替えるのはDB(Spring Dataのリポジトリ。メモリ上の実装)と、接続先の名前解決だけ。
 */
public final class PluginFixture implements AutoCloseable {

    public final PlatformWorld world = new PlatformWorld().actingAsAdmin(1L);
    public final List<PluginRegistration> systemStore = new ArrayList<>();
    public final List<ProjectPluginRegistration> projectStore = new ArrayList<>();
    public final PluginRegistrationRepository systemRepository = InMemoryRepositories.pluginRegistrations(systemStore);
    public final ProjectPluginRegistrationRepository projectRepository =
            InMemoryRepositories.projectPluginRegistrations(projectStore);
    public final PluginRegistrationService systemService;
    public final ProjectPluginRegistrationService projectService;

    public PluginFixture() {
        // lbx-ollama は docker ネットワーク内(172.20.0.9)、gpu-pc.example はLAN(192.168.1.50)、loopback.example は禁止アドレス。
        systemService = new PluginRegistrationService(systemRepository, projectRepository,
                world.adminAuthorizationService,
                new SystemDestinationPolicy(host -> resolve(host)), world.credentialCipher);
        ConnectionDestinationGuard guard = new ConnectionDestinationGuard(
                PluginFixture::resolve,
                () -> List.of(new InterfaceAddr(addressBytes("172.20.0.2"), 16)),
                () -> true);
        projectService = new ProjectPluginRegistrationService(
                projectRepository, world.adminAuthorizationService, guard, world.credentialCipher);
    }

    private static InetAddress[] resolve(String host) throws UnknownHostException {
        return switch (host) {
            case "lbx-ollama" -> new InetAddress[] {InetAddress.getByName("172.20.0.9")};
            case "gpu-pc.example" -> new InetAddress[] {InetAddress.getByName("192.168.1.50")};
            case "loopback.example" -> new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            default -> throw new UnknownHostException(host);
        };
    }

    private static byte[] addressBytes(String address) {
        try {
            return InetAddress.getByName(address).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        world.close();
    }
}
