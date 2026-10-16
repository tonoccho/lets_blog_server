package com.letsblog.content.service;

import com.letsblog.content.domain.CustomTag;
import com.letsblog.content.domain.CustomTagFormat;
import com.letsblog.content.domain.CustomTagTemplate;
import com.letsblog.content.dto.ApplyCustomTagTemplateRequest;
import com.letsblog.content.dto.CustomTagResponse;
import com.letsblog.content.dto.CloneCustomTagTemplateRequest;
import com.letsblog.content.dto.CustomTagTemplateRequest;
import com.letsblog.content.dto.CustomTagTemplateResponse;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * issue #656: #641のレビューで、CustomTag*サービス群のうちCustomTagTemplateServiceが未テストの
 * まま残っていたことを受けて追加。主な責務(作成・公開/非公開・複製・一覧/検索/フィルタ)の主経路を検証する。
 *
 * <p>issue #1821: CurrentActorService・AdminAuthorizationService・CustomTagValidationServiceは実物。
 * identity-serviceはHTTPフェイク、リポジトリ(DB)だけが差し替え(保存した行はメモリに持つ。
 * JPQLの検索クエリはDBの仕事なので、リポジトリの戻り値を指定する)。
 */
class CustomTagTemplateServiceTest {

    private ContentWorld world;
    private CustomTagTemplateService service;

    @BeforeEach
    void setUp() {
        world = new ContentWorld().actingAsAdmin(7L);
        service = world.customTagTemplateService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private CustomTagTemplateRequest buildRequest() {
        return new CustomTagTemplateRequest(
                "note-template", "desc", "alert", "<div>{{content}}</div>", ".note{color:red;}", null, null);
    }

    /** DB(メモリ上のリポジトリ)に未公開のテンプレートを用意する。最初の行のidは1になる。 */
    private CustomTagTemplate buildTemplate() {
        return world.template("note-template", "<div>{{content}}</div>", ".note{color:red;}", false, null);
    }

    @Test
    void create_バージョン1未公開の新規テンプレートとして保存する() {
        CustomTagTemplateResponse response = service.create(buildRequest());

        assertEquals(1L, response.id());
        assertEquals("note-template", response.templateName());
        assertEquals(1, response.version());
        assertFalse(response.isPublished());
        assertEquals(7L, response.createdBy());
    }

    @Test
    void create_admin権限が無ければForbiddenExceptionで保存されない() {
        world.actingAsUser(7L);

        assertThrows(ForbiddenException.class, () -> service.create(buildRequest()));
        assertTrue(world.savedTemplates.isEmpty());
    }

    @Test
    void update_存在するテンプレートを更新する() {
        buildTemplate();
        CustomTagTemplateRequest request = new CustomTagTemplateRequest(
                "renamed", "d2", "info", "<p>{{content}}</p>", ".p{}", null, null);

        CustomTagTemplateResponse response = service.update(1L, request);

        assertEquals("renamed", response.templateName());
        assertEquals("d2", response.description());
        assertEquals("info", response.category());
        assertEquals("<p>{{content}}</p>", world.templates.get(1L).getHtmlTemplate());
    }

    @Test
    void update_存在しないidはCustomTagTemplateNotFoundException() {
        assertThrows(CustomTagTemplateNotFoundException.class, () -> service.update(99L, buildRequest()));
    }

    @Test
    void publish_isPublishedをtrueにする() {
        buildTemplate();

        CustomTagTemplateResponse response = service.publish(1L);

        assertTrue(response.isPublished());
    }

    @Test
    void publish_存在しないidはCustomTagTemplateNotFoundException() {
        assertThrows(CustomTagTemplateNotFoundException.class, () -> service.publish(99L));
    }

    @Test
    void unpublish_isPublishedをfalseにする() {
        buildTemplate().setIsPublished(true);

        CustomTagTemplateResponse response = service.unpublish(1L);

        assertFalse(response.isPublished());
    }

    @Test
    void unpublish_存在しないidはCustomTagTemplateNotFoundException() {
        assertThrows(CustomTagTemplateNotFoundException.class, () -> service.unpublish(99L));
    }

    @Test
    void clone_元テンプレートのHTML_CSSを引き継ぎバージョン1未公開の新規テンプレートを作る() {
        CustomTagTemplate original = buildTemplate();
        original.setDescription("元の説明");
        original.setCategory("alert");
        original.setProjectId(5L);
        original.setVersion(3);
        original.setIsPublished(true);
        world.actingAsAdmin(9L);

        CloneCustomTagTemplateRequest request = new CloneCustomTagTemplateRequest("cloned-template", null, null, null);
        CustomTagTemplateResponse response = service.clone(1L, request);

        assertEquals("cloned-template", response.templateName());
        assertEquals("<div>{{content}}</div>", response.htmlTemplate());
        assertEquals(".note{color:red;}", response.cssContent());
        assertEquals("元の説明", response.description());
        assertEquals("alert", response.category());
        assertEquals(5L, response.projectId());
        assertEquals(1, response.version());
        assertFalse(response.isPublished());
        assertEquals(9L, response.createdBy());
        // 元のテンプレートは変わらず、custom_tagsの行も作らない
        assertEquals(3, original.getVersion());
        assertTrue(world.savedTags.isEmpty());
    }

    @Test
    void clone_リクエストで指定された値は元テンプレートより優先される() {
        CustomTagTemplate original = buildTemplate();
        original.setDescription("元の説明");
        original.setCategory("alert");
        original.setProjectId(5L);

        CloneCustomTagTemplateRequest request =
                new CloneCustomTagTemplateRequest("cloned-template", "新しい説明", "info", 8L);
        CustomTagTemplateResponse response = service.clone(1L, request);

        assertEquals("新しい説明", response.description());
        assertEquals("info", response.category());
        assertEquals(8L, response.projectId());
    }

    @Test
    void clone_存在しないidはCustomTagTemplateNotFoundException() {
        CloneCustomTagTemplateRequest request = new CloneCustomTagTemplateRequest("cloned", null, null, null);
        assertThrows(CustomTagTemplateNotFoundException.class, () -> service.clone(99L, request));
    }

    @Test
    void delete_存在するテンプレートを削除する() {
        buildTemplate();

        service.delete(1L);

        assertFalse(world.templates.containsKey(1L));
    }

    @Test
    void delete_存在しないidはCustomTagTemplateNotFoundException() {
        buildTemplate();

        assertThrows(CustomTagTemplateNotFoundException.class, () -> service.delete(99L));
        assertTrue(world.templates.containsKey(1L));
    }

    @Test
    void getById_存在するテンプレートを返す() {
        buildTemplate();

        CustomTagTemplateResponse response = service.getById(1L);

        assertEquals(1L, response.id());
    }

    @Test
    void getById_存在しないidはCustomTagTemplateNotFoundException() {
        assertThrows(CustomTagTemplateNotFoundException.class, () -> service.getById(99L));
    }

    /**
     * issue #1220: GET /api/custom-tag-templates/{id} には認可チェックが無く、非メンバーが
     * 他プロジェクトの未公開テンプレートを読めていた。#1057と同じ方針
     * (isPublished=false かつ projectId 指定時のみプロジェクトメンバー判定)を適用する。
     */
    @Test
    void getById_非公開かつprojectId指定時はプロジェクトメンバー判定を行う() {
        world.template("t", "<div></div>", null, false, 5L);
        world.actingAsUser(3L).projectMember(5L, 3L, true);

        service.getById(1L);

        assertEquals(1, world.http.requests("GET", "/api/internal/identity/projects/5/members/3").size());
    }

    @Test
    void getById_非公開テンプレートで他プロジェクトのメンバーでなければForbiddenExceptionが伝播する() {
        world.template("t", "<div></div>", null, false, 5L);
        world.actingAsUser(3L).projectMember(5L, 3L, false);

        assertThrows(ForbiddenException.class, () -> service.getById(1L));
    }

    @Test
    void getById_公開済みならプロジェクトメンバー判定を行わない() {
        world.template("t", "<div></div>", null, true, 5L);
        world.actingAsUser(3L);

        CustomTagTemplateResponse response = service.getById(1L);

        assertTrue(response.isPublished());
        assertTrue(world.http.requests("GET", "/api/internal/identity/projects/.*").isEmpty());
    }

    /**
     * projectId未指定(グローバルテンプレート)は list()/listPublished() 等と同じ既存の規約
     * (projectId=nullは「グローバル」としてプロジェクト単位の判定対象がそもそも無い)ため、
     * 未公開でもプロジェクトメンバー判定は経由しない。
     */
    @Test
    void getById_非公開でもprojectId未指定ならプロジェクトメンバー判定を行わない() {
        buildTemplate();
        world.actingAsUser(3L);

        service.getById(1L);

        assertTrue(world.http.requests("GET", "/api/internal/identity/projects/.*").isEmpty());
    }

    @Test
    void listPublished_projectId未指定ならグローバル公開テンプレートのみ() {
        List<CustomTagTemplate> rows = List.of(buildTemplate());
        when(world.customTagTemplateRepository.findPublishedGlobalTemplates()).thenReturn(rows);

        List<CustomTagTemplateResponse> result = service.listPublished(null);

        assertEquals(1, result.size());
    }

    @Test
    void listPublished_projectId指定時はプロジェクト固有とグローバルの公開テンプレート() {
        List<CustomTagTemplate> rows = List.of(buildTemplate(), buildTemplate());
        when(world.customTagTemplateRepository.findPublishedTemplatesByProject(5L)).thenReturn(rows);

        List<CustomTagTemplateResponse> result = service.listPublished(5L);

        assertEquals(2, result.size());
    }

    @Test
    void list_projectId未指定ならグローバルテンプレートのみ() {
        List<CustomTagTemplate> rows = List.of(buildTemplate());
        when(world.customTagTemplateRepository.findGlobalTemplates()).thenReturn(rows);

        List<CustomTagTemplateResponse> result = service.list(null);

        assertEquals(1, result.size());
    }

    @Test
    void list_projectId指定時はプロジェクトの全テンプレート() {
        List<CustomTagTemplate> rows = List.of(buildTemplate());
        when(world.customTagTemplateRepository.findAllTemplatesByProject(5L)).thenReturn(rows);

        List<CustomTagTemplateResponse> result = service.list(5L);

        assertEquals(1, result.size());
    }

    /**
     * issue #1057: GET /api/custom-tag-templates?projectId=&showAll=true は、公開状態を問わず
     * 全件返す(findAllTemplatesByProject は isPublished を条件にしない)にもかかわらず
     * プロジェクトメンバー判定が無く、非メンバーが他プロジェクトの未公開テンプレートを読めていた。
     * searchByKeyword/filterByCategoryは isPublished=true を条件にしたクエリのため対象外
     * (このIssueの対象は list() のみ)。
     */
    @Test
    void list_projectId指定時はプロジェクトメンバー判定を行う() {
        world.actingAsUser(3L).projectMember(5L, 3L, true);

        service.list(5L);

        assertEquals(1, world.http.requests("GET", "/api/internal/identity/projects/5/members/3").size());
    }

    @Test
    void list_プロジェクトメンバーでなければForbiddenExceptionが伝播する() {
        world.actingAsUser(3L).projectMember(5L, 3L, false);

        assertThrows(ForbiddenException.class, () -> service.list(5L));
    }

    /**
     * projectId 未指定(グローバルテンプレートのみ)はプロジェクト単位の判定対象がそもそも無いため、
     * requireProjectMemberOrAdmin は経由しない(list() の null 分岐は元から変更していない)。
     */
    @Test
    void list_projectId未指定ならプロジェクトメンバー判定を行わない() {
        world.actingAsUser(3L);

        service.list(null);

        assertTrue(world.http.requests("GET", "/api/internal/identity/projects/.*").isEmpty());
    }

    @Test
    void searchByKeyword_projectId未指定ならグローバル検索() {
        List<CustomTagTemplate> rows = List.of(buildTemplate());
        when(world.customTagTemplateRepository.searchByKeyword("note")).thenReturn(rows);

        List<CustomTagTemplateResponse> result = service.searchByKeyword("note", null);

        assertEquals(1, result.size());
    }

    @Test
    void searchByKeyword_projectId指定時はプロジェクトとグローバルで検索() {
        List<CustomTagTemplate> rows = List.of(buildTemplate());
        when(world.customTagTemplateRepository.searchByKeywordAndProject("note", 5L)).thenReturn(rows);

        List<CustomTagTemplateResponse> result = service.searchByKeyword("note", 5L);

        assertEquals(1, result.size());
    }

    @Test
    void filterByCategory_projectId未指定ならグローバルフィルタ() {
        List<CustomTagTemplate> rows = List.of(buildTemplate());
        when(world.customTagTemplateRepository.findByCategory("alert")).thenReturn(rows);

        List<CustomTagTemplateResponse> result = service.filterByCategory("alert", null);

        assertEquals(1, result.size());
    }

    @Test
    void filterByCategory_projectId指定時はプロジェクトとグローバルでフィルタ() {
        List<CustomTagTemplate> rows = List.of(buildTemplate());
        when(world.customTagTemplateRepository.findByCategoryAndProject("alert", 5L)).thenReturn(rows);

        List<CustomTagTemplateResponse> result = service.filterByCategory("alert", 5L);

        assertEquals(1, result.size());
    }

    @Test
    void getMyTemplates_ログイン中ユーザーが作成したテンプレートのみ() {
        List<CustomTagTemplate> rows = List.of(buildTemplate());
        when(world.customTagTemplateRepository.findByCreatedBy(7L)).thenReturn(rows);

        List<CustomTagTemplateResponse> result = service.getMyTemplates();

        assertEquals(1, result.size());
        // identity-serviceが解決した操作者のIDで検索している
        assertEquals(1, world.http.requests("GET", "/api/identity/me").size());
    }

    // ---- issue #1131: テンプレートをプロジェクトの custom_tags 行として適用する(apply) ----

    private CustomTagTemplate applicableTemplate(boolean published, Long templateProjectId) {
        CustomTagTemplate template = world.template(
                "note-template", "<div class=\"note\">{{content}}</div>", ".note{color:red;}", published,
                templateProjectId);
        template.setDescription("注意書き");
        return template;
    }

    @Test
    void apply_テンプレートのHTML_CSSから対象プロジェクトのcustom_tags行を作る() {
        applicableTemplate(true, null);

        CustomTagResponse response = service.apply(1L, new ApplyCustomTagTemplateRequest(5L, "note"));

        assertEquals(1L, response.id());
        assertEquals("note", response.tagName());
        assertEquals(5L, response.projectId());
        assertEquals("<div class=\"note\">{{content}}</div>", response.htmlTemplate());
        assertEquals(".note{color:red;}", response.cssContent());
        assertEquals("注意書き", response.description());
        assertEquals(CustomTagFormat.BLOCK, response.tagFormat());
    }

    @Test
    void apply_同名タグが既にあれば上書きせずIllegalArgumentException() {
        applicableTemplate(true, null);
        CustomTag existing = world.tag("note", "<p></p>", null, CustomTagFormat.BLOCK, 5L);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.apply(1L, new ApplyCustomTagTemplateRequest(5L, "note")));

        assertTrue(e.getMessage().contains("note"));
        assertTrue(e.getMessage().contains("既に登録されています"));
        assertEquals(1, world.savedTags.size());
        assertEquals("<p></p>", existing.getHtmlTemplate());
    }

    @Test
    void apply_存在しないテンプレートはCustomTagTemplateNotFoundException() {
        assertThrows(CustomTagTemplateNotFoundException.class,
                () -> service.apply(99L, new ApplyCustomTagTemplateRequest(5L, "note")));
        assertTrue(world.savedTags.isEmpty());
    }

    @Test
    void apply_対象プロジェクトのメンバーでなければForbiddenで何も作らない() {
        applicableTemplate(true, null);
        world.actingAsUser(3L).projectMember(5L, 3L, false);

        assertThrows(ForbiddenException.class,
                () -> service.apply(1L, new ApplyCustomTagTemplateRequest(5L, "note")));
        assertTrue(world.savedTags.isEmpty());
    }

    @Test
    void apply_他プロジェクトの未公開テンプレートはそのプロジェクトのメンバーでなければ適用できない() {
        applicableTemplate(false, 8L);
        world.actingAsUser(3L).projectMember(5L, 3L, true).projectMember(8L, 3L, false);

        assertThrows(ForbiddenException.class,
                () -> service.apply(1L, new ApplyCustomTagTemplateRequest(5L, "note")));
        assertTrue(world.savedTags.isEmpty());
    }

    @Test
    void apply_他プロジェクトの未公開テンプレートでも両方のメンバーなら適用できる() {
        applicableTemplate(false, 8L);
        world.actingAsUser(3L).projectMember(5L, 3L, true).projectMember(8L, 3L, true);

        CustomTagResponse response = service.apply(1L, new ApplyCustomTagTemplateRequest(5L, "note"));

        assertEquals(5L, response.projectId());
    }

    @Test
    void apply_未公開でもグローバルテンプレートならテンプレート側のメンバー判定は行わない() {
        applicableTemplate(false, null);
        world.actingAsUser(3L).projectMember(5L, 3L, true);

        service.apply(1L, new ApplyCustomTagTemplateRequest(5L, "note"));

        assertEquals(1, world.http.requests("GET", "/api/internal/identity/projects/.*").size());
    }

    @Test
    void apply_HTMLがセキュリティ検証に通らなければInvalidCustomTagContentExceptionで何も作らない() {
        world.template("bad", "<div>{{content}}<script>alert(1)</script></div>", null, true, null);

        InvalidCustomTagContentException e = assertThrows(InvalidCustomTagContentException.class,
                () -> service.apply(1L, new ApplyCustomTagTemplateRequest(5L, "note")));

        assertTrue(e.getMessage().contains("セキュリティ要件を満たしていません"));
        assertTrue(world.savedTags.isEmpty());
    }
}
