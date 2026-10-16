package com.letsblog.content.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.letsblog.content.dto.CustomTagTemplateResponse;
import com.letsblog.content.service.ForbiddenException;
import com.letsblog.content.testsupport.ContentWorld;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 一覧の絞り込み(search → category → showAll → 公開済み)の分岐。コントローラ・サービス・認可は実物で、
 * 差し替えるのは DB(Spring Data のリポジトリ)と identity-service の HTTP(フェイクサーバー)だけ。
 */
class CustomTagTemplateControllerTest {

    private ContentWorld world;
    private CustomTagTemplateController controller;

    @BeforeEach
    void setUp() {
        world = new ContentWorld();
        controller = new CustomTagTemplateController(world.customTagTemplateService);
        world.actingAsAdmin(1L);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private List<String> names(List<CustomTagTemplateResponse> body) {
        return body.stream().map(CustomTagTemplateResponse::templateName).toList();
    }

    @Test
    void 検索語があればキーワード検索を返す_プロジェクト指定なし() {
        var t = world.template("検索ヒット", "<p/>", null, true, null);
        when(world.customTagTemplateRepository.searchByKeyword("ヒット")).thenReturn(List.of(t));

        var response = controller.list(null, "カテゴリ", "ヒット", true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(names(response.getBody())).containsExactly("検索ヒット");
    }

    @Test
    void 検索語があればキーワード検索を返す_プロジェクト指定あり() {
        var t = world.template("プロジェクト検索", "<p/>", null, true, 5L);
        when(world.customTagTemplateRepository.searchByKeywordAndProject("検索", 5L)).thenReturn(List.of(t));

        assertThat(names(controller.list(5L, null, "検索", false).getBody())).containsExactly("プロジェクト検索");
    }

    @Test
    void 検索語が空白ならカテゴリで絞る() {
        var t = world.template("カテゴリ一致", "<p/>", null, true, null);
        when(world.customTagTemplateRepository.findByCategory("blog")).thenReturn(List.of(t));

        assertThat(names(controller.list(null, "blog", "  ", false).getBody())).containsExactly("カテゴリ一致");
    }

    @Test
    void カテゴリ指定とプロジェクト指定ならプロジェクト内のカテゴリで絞る() {
        var t = world.template("プロジェクトのカテゴリ", "<p/>", null, true, 5L);
        when(world.customTagTemplateRepository.findByCategoryAndProject("blog", 5L)).thenReturn(List.of(t));

        assertThat(names(controller.list(5L, "blog", null, false).getBody())).containsExactly("プロジェクトのカテゴリ");
    }

    @Test
    void カテゴリが空白でshowAllなら公開状態を問わずグローバルを全件返す() {
        var draft = world.template("下書き", "<p/>", null, false, null);
        when(world.customTagTemplateRepository.findGlobalTemplates()).thenReturn(List.of(draft));

        assertThat(names(controller.list(null, "", null, true).getBody())).containsExactly("下書き");
    }

    @Test
    void showAllでプロジェクト指定ならメンバーまたはadminだけが全件を見られる() {
        var draft = world.template("プロジェクトの下書き", "<p/>", null, false, 5L);
        when(world.customTagTemplateRepository.findAllTemplatesByProject(5L)).thenReturn(List.of(draft));

        assertThat(names(controller.list(5L, null, null, true).getBody())).containsExactly("プロジェクトの下書き");
    }

    @Test
    void showAllでプロジェクト指定でも非メンバーは拒否される() {
        world.actingAsUser(2L);

        assertThatThrownBy(() -> controller.list(5L, null, null, true)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void 何も指定しなければ公開済みだけを返す() {
        var published = world.template("公開済み", "<p/>", null, true, null);
        when(world.customTagTemplateRepository.findPublishedGlobalTemplates()).thenReturn(List.of(published));

        assertThat(names(controller.list(null, null, null, false).getBody())).containsExactly("公開済み");
    }

    @Test
    void プロジェクト指定で公開済みだけを返す() {
        var published = world.template("プロジェクトの公開済み", "<p/>", null, true, 5L);
        when(world.customTagTemplateRepository.findPublishedTemplatesByProject(5L)).thenReturn(List.of(published));

        assertThat(names(controller.list(5L, null, null, false).getBody())).containsExactly("プロジェクトの公開済み");
    }
}
