package com.letsblog.platform.integration;

import com.letsblog.platform.domain.PluginKind;
import com.letsblog.platform.domain.PluginRegistration;
import com.letsblog.platform.repository.PluginRegistrationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** プラグインの登録の保存先(issue #1807)を、実DB(lbs_platform_test)のFlywayスキーマとJPAのマッピングで確かめる。 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("platform-service: プラグインの登録の保存(issue #1807)")
class PluginRegistrationRepositoryIntegrationTest extends IdentityFakeIntegrationTestBase {

    @Autowired
    private PluginRegistrationRepository repository;

    @MockitoBean
    private JwtDecoder jwtDecoder;
    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    private PluginRegistration saved(PluginKind kind, String name, boolean inUse) {
        PluginRegistration r = new PluginRegistration(kind, name, "http://lbx-" + name + ":1", null);
        r.setInUse(inUse);
        return repository.save(r);
    }

    @Test
    @DisplayName("保存すると採番され、作成・更新日時が入り、drawioUrlは図のレンダリングで保てる")
    void 保存と読み出し() {
        PluginRegistration r = new PluginRegistration(
                PluginKind.DIAGRAM_RENDERER, "図", "http://lbx-plantuml:8085", "https://drawio.example/");
        PluginRegistration saved = repository.save(r);

        PluginRegistration found = repository.findById(saved.getId()).orElseThrow();
        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
        assertEquals("https://drawio.example/", found.getDrawioUrl());
        assertEquals(PluginKind.DIAGRAM_RENDERER, found.getKind());
    }

    @Test
    @DisplayName("種類ごとの件数・使用中・種類順の一覧を引ける")
    void 種類ごとの検索() {
        saved(PluginKind.LLM, "a", false);
        PluginRegistration inUse = saved(PluginKind.LLM, "b", true);
        saved(PluginKind.PENPOT, "p", false);

        assertEquals(2, repository.countByKind(PluginKind.LLM));
        assertEquals(0, repository.countByKind(PluginKind.IMAGE_GENERATION));
        assertEquals(inUse.getId(), repository.findFirstByKindAndInUseTrue(PluginKind.LLM).orElseThrow().getId());
        assertTrue(repository.findFirstByKindAndInUseTrue(PluginKind.PENPOT).isEmpty());
        assertEquals(List.of("a", "b"),
                repository.findByKindOrderByIdAsc(PluginKind.LLM).stream().map(PluginRegistration::getName).toList());
        assertEquals(List.of("LLM", "LLM", "PENPOT"), repository.findAllByOrderByKindAscIdAsc().stream()
                .map(r -> r.getKind().name()).toList());
    }
}
