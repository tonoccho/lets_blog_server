package com.letsblog.platform.integration;

import com.letsblog.platform.domain.SystemSetting;
import com.letsblog.platform.repository.SystemSettingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * system_settingsの保存(JPAのライフサイクル)を、実DB(lbs_platform_test)で確かめる(issue #1825)。
 * 作成日時の補完({@code @PrePersist})と更新日時の更新({@code @PreUpdate})。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("platform-service: system_settingsの保存")
class SystemSettingPersistenceIntegrationTest extends IdentityFakeIntegrationTestBase {

    private static final String KEY = "persistence_test_key";

    @Autowired
    private SystemSettingRepository repository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @AfterEach
    void cleanUp() {
        repository.deleteById(KEY);
    }

    @Test
    @DisplayName("更新日時を持たないエンティティを保存すると、保存時に更新日時が入る")
    void 更新日時が無ければ保存時に補う() {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(KEY);
        setting.setSettingValueEncrypted(new byte[] {1, 2, 3});

        repository.saveAndFlush(setting);

        SystemSetting found = repository.findById(KEY).orElseThrow();
        assertNotNull(found.getUpdatedAt());
        assertEquals(3, found.getSettingValueEncrypted().length);
    }

    @Test
    @DisplayName("更新日時を持つエンティティは、その値のまま保存され、更新すると更新日時が進む")
    void 更新日時は更新で進む() throws Exception {
        repository.saveAndFlush(new SystemSetting(KEY, new byte[] {1}));
        SystemSetting created = repository.findById(KEY).orElseThrow();
        java.time.LocalDateTime first = created.getUpdatedAt();
        // DATETIMEは秒の精度なので、更新日時が進んだと分かるよう1秒以上空ける。
        Thread.sleep(1100);

        created.setSettingValueEncrypted(new byte[] {2});
        repository.saveAndFlush(created);

        assertTrue(repository.findById(KEY).orElseThrow().getUpdatedAt().isAfter(first));
    }
}
