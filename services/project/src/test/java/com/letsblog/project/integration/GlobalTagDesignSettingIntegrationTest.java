package com.letsblog.project.integration;

import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.domain.TagDesignSetting;
import com.letsblog.project.dto.SaveTagDesignSettingRequest;
import com.letsblog.project.dto.TagDesignColors;
import com.letsblog.project.repository.TagDesignSettingRepository;
import com.letsblog.project.service.TagDesignSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * issue #763: {@code tag_design_settings} に {@code project_id IS NULL} の
 * グローバル既定行を持てることを、実スキーマに対して確認する。
 *
 * <p>単体テストはリポジトリをモックするため、マイグレーションV2が実際に効いているか
 * (NOT NULL が外れたか、グローバル行の一意性が担保されているか)は検証できない。
 * ADR-0006 のとおり Testcontainers は使わず実 MySQL の {@code lbs_project_test} に接続する。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("project-service: グローバル既定タグデザイン(issue #763)")
class GlobalTagDesignSettingIntegrationTest {

    @Autowired
    private TagDesignSettingService service;

    @Autowired
    private TagDesignSettingRepository repository;

    @BeforeEach
    @AfterEach
    void クリーンアップ() {
        repository.findByProjectIdIsNull().forEach(repository::delete);
    }

    @Test
    @DisplayName("project_id IS NULL の行を作成でき、再取得できる")
    void グローバル行を作成できる() {
        service.save(null, EmbedTagType.TOC, new SaveTagDesignSettingRequest(
                "light", "#101010", "#f0f0f0", "#3399ff", ".toc{border:0}", "<div>g</div>"));

        TagDesignSetting stored = repository.findByProjectIdIsNullAndTagType(EmbedTagType.TOC).orElseThrow();
        assertThat(stored.getProjectId()).isNull();
        assertThat(stored.getBackgroundColor()).isEqualTo("#101010");
        assertThat(stored.getHtmlTemplate()).isEqualTo("<div>g</div>");
    }

    @Test
    @DisplayName("同じタグ種別を再保存すると更新になる(行が増えない)")
    void グローバル行を更新できる() {
        service.save(null, EmbedTagType.TOC, new SaveTagDesignSettingRequest(
                "light", "#101010", "#f0f0f0", "#3399ff", null, null));
        service.save(null, EmbedTagType.TOC, new SaveTagDesignSettingRequest(
                "light", "#202020", "#e0e0e0", "#ff9933", null, null));

        assertThat(repository.findByProjectIdIsNull()).hasSize(1);
        assertThat(repository.findByProjectIdIsNullAndTagType(EmbedTagType.TOC).orElseThrow()
                .getBackgroundColor()).isEqualTo("#202020");
    }

    @Test
    @DisplayName("解決がグローバル行を引く。行が無ければ標準プリセットへ戻る")
    void 解決がグローバル行を引く() {
        assertThat(service.resolveColors(null, EmbedTagType.BLOGCARD).backgroundColor())
                .as("グローバル行が無い状態では標準プリセット")
                .isNotEqualTo("#123456");

        service.save(null, EmbedTagType.BLOGCARD, new SaveTagDesignSettingRequest(
                "light", "#123456", "#abcdef", "#0088cc", null, "<span>bc</span>"));

        TagDesignColors colors = service.resolveColors(null, EmbedTagType.BLOGCARD);
        assertThat(colors.backgroundColor()).isEqualTo("#123456");
        assertThat(service.resolveHtmlTemplate(null, EmbedTagType.BLOGCARD)).isEqualTo("<span>bc</span>");
    }

    /**
     * MySQL の UNIQUE は NULL 同士を異なる値として扱うため、
     * {@code UNIQUE (project_id, tag_type)} のままでは {@code (NULL, 'TOC')} を何行でも作れてしまう。
     * V2 で導入した生成カラム {@code project_scope}(NULL を -1 に畳む)への一意制約が
     * それを止めていることを、リポジトリ経由の直接挿入で確認する。
     * これが効いていないと {@code findByProjectIdIsNullAndTagType} が複数行にぶつかって落ちる。
     */
    @Test
    @DisplayName("グローバル行はタグ種別ごとに1行しか作れない(DBが二重作成を止める)")
    void グローバル行の二重作成をDBが止める() {
        service.save(null, EmbedTagType.AMAZON, new SaveTagDesignSettingRequest(
                "light", "#101010", "#f0f0f0", "#3399ff", null, null));

        TagDesignSetting duplicate = new TagDesignSetting();
        duplicate.setProjectId(null);
        duplicate.setTagType(EmbedTagType.AMAZON);
        duplicate.setPresetId("light");
        duplicate.setBackgroundColor("#999999");
        duplicate.setTextColor("#000000");
        duplicate.setAccentColor("#111111");

        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
