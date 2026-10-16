package com.letsblog.content.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.content.service.ForbiddenException;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * プロジェクトのコンテンツ設定(CSSセレクタ接頭辞)。コントローラ・サービス・認可は実物で、差し替えるのは
 * DB(リポジトリ)と identity-service / project-service の HTTP(フェイクサーバー)だけ。
 */
class ProjectContentSettingsControllerTest {

    private ContentWorld world;
    private ProjectContentSettingsController controller;

    @BeforeEach
    void setUp() {
        world = new ContentWorld();
        controller = new ProjectContentSettingsController(
                world.projectContentSettingsService, world.adminAuthorizationService);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void 未設定のプロジェクトはnullを返す() {
        world.actingAsAdmin(1L);

        assertThat(controller.get(7L).cssSelectorPrefix()).isNull();
    }

    @Test
    void 設定済みの接頭辞を返す() {
        world.actingAsAdmin(1L);
        world.settingsFor(7L, "blog");

        assertThat(controller.get(7L).cssSelectorPrefix()).isEqualTo("blog");
    }

    @Test
    void プロジェクトのメンバーは参照でき_メンバーでなければ拒否される() {
        world.actingAsUser(2L).projectMember(7L, 2L, true);
        assertThat(controller.get(7L).cssSelectorPrefix()).isNull();

        world.projectMember(7L, 2L, false);
        assertThatThrownBy(() -> controller.get(7L)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void adminは接頭辞を更新でき_更新はサイトへの同期を依頼する() {
        world.actingAsAdmin(1L);

        var response = controller.updateCssSelectorPrefix(
                7L, new ProjectContentSettingsController.UpdateCssSelectorPrefixRequest("my-prefix"));

        assertThat(response.cssSelectorPrefix()).isEqualTo("my-prefix");
        assertThat(world.settings.get(7L).getCssSelectorPrefix()).isEqualTo("my-prefix");
        assertThat(world.http.requests("POST", "/api/internal/project/letsblog-sync")).hasSize(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void 空やnullや空白だけの接頭辞は未設定として保存する(String blank) {
        world.actingAsAdmin(1L);
        world.settingsFor(7L, "old");

        var response = controller.updateCssSelectorPrefix(
                7L, new ProjectContentSettingsController.UpdateCssSelectorPrefixRequest(blank));

        assertThat(response.cssSelectorPrefix()).isNull();
        assertThat(world.settings.get(7L).getCssSelectorPrefix()).isNull();
    }

    @Test
    void admin以外は更新できず_何も保存しない() {
        world.actingAsUser(2L);

        assertThatThrownBy(() -> controller.updateCssSelectorPrefix(
                7L, new ProjectContentSettingsController.UpdateCssSelectorPrefixRequest("x")))
                .isInstanceOf(ForbiddenException.class);
        assertThat(world.settings).isEmpty();
    }
}
