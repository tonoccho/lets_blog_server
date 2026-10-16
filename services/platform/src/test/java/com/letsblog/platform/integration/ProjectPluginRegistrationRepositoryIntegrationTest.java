package com.letsblog.platform.integration;

import com.letsblog.common.client.IdentityClient;
import com.letsblog.platform.domain.PluginKind;
import com.letsblog.platform.domain.ProjectPluginRegistration;
import com.letsblog.platform.repository.ProjectPluginRegistrationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** プロジェクト単位の上書きの保存先(issue #1808)を、実DB(lbs_platform_test)のFlywayスキーマ(V6)とJPAのマッピングで確かめる。 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("platform-service: プロジェクト単位のプラグインの登録の保存(issue #1808)")
class ProjectPluginRegistrationRepositoryIntegrationTest {

    @Autowired
    private ProjectPluginRegistrationRepository repository;

    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private IdentityClient identityClient;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    private ProjectPluginRegistration saved(long projectId, PluginKind kind, String name, boolean inUse) {
        ProjectPluginRegistration r = new ProjectPluginRegistration(projectId, kind, name, "http://gpu-" + name + ":1", null);
        r.setInUse(inUse);
        return repository.save(r);
    }

    @Test
    @DisplayName("保存すると採番され、日時と暗号化済みの認証情報を保てる")
    void 保存と読み出し() {
        ProjectPluginRegistration r = new ProjectPluginRegistration(10L, PluginKind.LLM, "a", "http://gpu:1", null);
        r.setCredentialEncrypted(new byte[] {1, 2, 3});

        ProjectPluginRegistration found = repository.findById(repository.save(r).getId()).orElseThrow();

        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
        assertEquals(10L, found.getProjectId());
        assertArrayEquals(new byte[] {1, 2, 3}, found.getCredentialEncrypted());
    }

    @Test
    @DisplayName("プロジェクトと種類で件数・使用中・一覧・IDを引ける。他のプロジェクトの登録は混ざらない")
    void プロジェクトごとの検索() {
        saved(10L, PluginKind.LLM, "a", false);
        ProjectPluginRegistration inUse = saved(10L, PluginKind.LLM, "b", true);
        saved(10L, PluginKind.DIAGRAM_RENDERER, "d", false);
        ProjectPluginRegistration other = saved(20L, PluginKind.LLM, "x", true);

        assertEquals(2, repository.countByProjectIdAndKind(10L, PluginKind.LLM));
        assertEquals(0, repository.countByProjectIdAndKind(10L, PluginKind.IMAGE_GENERATION));
        assertEquals(inUse.getId(), repository.findFirstByProjectIdAndKindAndInUseTrue(10L, PluginKind.LLM).orElseThrow().getId());
        assertTrue(repository.findFirstByProjectIdAndKindAndInUseTrue(10L, PluginKind.DIAGRAM_RENDERER).isEmpty());
        assertEquals(List.of("a", "b"), repository.findByProjectIdAndKindOrderByIdAsc(10L, PluginKind.LLM).stream()
                .map(ProjectPluginRegistration::getName).toList());
        assertEquals(List.of("DIAGRAM_RENDERER", "LLM", "LLM"), repository.findByProjectIdOrderByKindAscIdAsc(10L).stream()
                .map(r -> r.getKind().name()).toList());
        assertTrue(repository.findByIdAndProjectId(other.getId(), 10L).isEmpty());
        assertTrue(repository.findByIdAndProjectId(other.getId(), 20L).isPresent());
    }
}
