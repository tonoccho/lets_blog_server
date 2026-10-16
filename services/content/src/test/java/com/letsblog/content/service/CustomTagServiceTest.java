package com.letsblog.content.service;

import com.letsblog.content.domain.CustomTag;
import com.letsblog.content.domain.CustomTagFormat;
import com.letsblog.content.dto.CustomTagRequest;
import com.letsblog.content.dto.CustomTagResponse;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #656: #641のレビューで、CustomTag*サービス群のうちCustomTagServiceが未テストのまま
 * 残っていたことを受けて追加。特にapplySelectorPrefix/prefixSelectorList
 * (管理者が入力したCSSをWordPressへ一括貼り付けするために`.prefix `を付与する、コメント/文字列
 * リテラル/波括弧の深さ/丸括弧の深さを1パスで追跡する手書きパーサ、issue #298/#307)を、previewCss
 * (DB未保存のCSSに対しても同じ変換を行う公開メソッド、issue #335)経由で検証する。
 * previewCssはprivateなapplySelectorPrefix/prefixSelectorListの唯一のpublicな入口。
 */
class CustomTagServiceTest {

    private static final Long PROJECT_ID = 42L;
    private static final String PREFIX = "myproj";

    private ContentWorld world;
    private CustomTagService service;

    @BeforeEach
    void setUp() {
        // issue #1821: 認可・CurrentActorService・ProjectBridgeClient・各タグの描画サービス・
        // ProjectContentSettingsServiceは実物。identity-service/project-serviceはHTTPフェイク、リポジトリ(DB)はメモリ。
        world = new ContentWorld().actingAsAdmin(1L);
        service = world.customTagService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private CustomTagRequest buildRequest(String tagName, Long projectId) {
        return new CustomTagRequest(tagName, "<div>{{content}}</div>", "desc", ".card{color:red;}", null, projectId);
    }

    /** DB(メモリ上のリポジトリ)に行を用意する。最初の行のidは1になる。 */
    private CustomTag buildTag(String tagName, Long projectId) {
        return world.tag(tagName, "<div>{{content}}</div>", null, CustomTagFormat.BLOCK, projectId);
    }

    // ------------------------------------------------------------------
    // create / update / delete / list / listByProject
    // ------------------------------------------------------------------

    @Test
    void create_タグ名が重複していなければ保存する() {
        CustomTagResponse response = service.create(buildRequest("note", null));

        assertEquals(1L, response.id());
        assertEquals("note", response.tagName());
        assertEquals(CustomTagFormat.BLOCK, response.tagFormat());
        assertEquals(1, world.savedTags.size());
    }

    @Test
    void create_形式を指定すればその形式で保存しプロジェクトを引き継ぐ() {
        CustomTagResponse response = service.create(
                new CustomTagRequest("inline", "<b>{{content}}</b>", "d", null, CustomTagFormat.INLINE, PROJECT_ID));

        assertEquals(CustomTagFormat.INLINE, response.tagFormat());
        assertEquals(PROJECT_ID, response.projectId());
    }

    @Test
    void create_同じタグ名でもプロジェクトが違えば保存できる() {
        buildTag("note", null);

        CustomTagResponse response = service.create(buildRequest("note", PROJECT_ID));

        assertEquals(PROJECT_ID, response.projectId());
    }

    @Test
    void create_同じタグ名が既に存在する場合はIllegalArgumentException() {
        buildTag("note", null);

        assertThrows(IllegalArgumentException.class, () -> service.create(buildRequest("note", null)));
        assertEquals(1, world.savedTags.size());
    }

    @Test
    void create_プロジェクト内で同じタグ名が既に存在する場合はIllegalArgumentException() {
        buildTag("note", PROJECT_ID);

        assertThrows(IllegalArgumentException.class, () -> service.create(buildRequest("note", PROJECT_ID)));
    }

    @Test
    void create_admin権限が無ければForbiddenExceptionが伝播し保存されない() {
        world.actingAsUser(2L);

        assertThrows(ForbiddenException.class, () -> service.create(buildRequest("note", null)));
        assertTrue(world.savedTags.isEmpty());
    }

    @Test
    void update_存在するタグを更新する() {
        buildTag("old-name", null);

        CustomTagResponse response = service.update(1L, buildRequest("note", 999L));

        // projectIdの変更は無視される(既存タグのprojectIdのまま)
        assertEquals(null, response.projectId());
        assertEquals("note", response.tagName());
        assertEquals("note", world.tags.get(1L).getTagName());
    }

    @Test
    void update_形式を指定すれば更新し_プロジェクト固有のタグは同じプロジェクト内で重複を調べる() {
        buildTag("old-name", PROJECT_ID);
        buildTag("taken", PROJECT_ID);

        CustomTagResponse response = service.update(1L,
                new CustomTagRequest("note", "<i>{{content}}</i>", "d", null, CustomTagFormat.INLINE, null));

        assertEquals(CustomTagFormat.INLINE, response.tagFormat());
        assertThrows(IllegalArgumentException.class, () -> service.update(1L, buildRequest("taken", null)));
    }

    @Test
    void update_存在しないidはCustomTagNotFoundException() {
        assertThrows(CustomTagNotFoundException.class, () -> service.update(99L, buildRequest("note", null)));
    }

    @Test
    void update_admin権限が無ければForbiddenException() {
        buildTag("old-name", null);
        world.actingAsUser(2L);

        assertThrows(ForbiddenException.class, () -> service.update(1L, buildRequest("note", null)));
        assertEquals("old-name", world.tags.get(1L).getTagName());
    }

    @Test
    void update_自分自身以外が同じタグ名を使っている場合はIllegalArgumentException() {
        buildTag("old-name", null);
        buildTag("note", null);

        assertThrows(IllegalArgumentException.class, () -> service.update(1L, buildRequest("note", null)));
    }

    @Test
    void update_自分自身と同じidが同じタグ名でヒットしても許可される() {
        buildTag("note", null);

        CustomTagResponse response = service.update(1L, buildRequest("note", null));

        assertEquals("note", response.tagName());
    }

    @Test
    void delete_存在するタグを削除する() {
        buildTag("note", null);

        service.delete(1L);

        assertFalse(world.tags.containsKey(1L));
    }

    @Test
    void delete_存在しないidはCustomTagNotFoundException() {
        buildTag("note", null);

        assertThrows(CustomTagNotFoundException.class, () -> service.delete(99L));
        assertTrue(world.tags.containsKey(1L));
    }

    @Test
    void delete_admin権限が無ければForbiddenException() {
        buildTag("note", null);
        world.actingAsUser(2L);

        assertThrows(ForbiddenException.class, () -> service.delete(1L));
        assertTrue(world.tags.containsKey(1L));
    }

    @Test
    void list_projectId未指定ならグローバルタグのみ() {
        buildTag("g", null);
        buildTag("p", PROJECT_ID);

        List<CustomTagResponse> result = service.list(null);

        assertEquals(1, result.size());
        assertEquals("g", result.get(0).tagName());
    }

    @Test
    void list_projectId指定時はプロジェクト固有とグローバルの両方() {
        buildTag("g", null);
        buildTag("p", PROJECT_ID);
        buildTag("other", 7L);

        List<CustomTagResponse> result = service.list(PROJECT_ID);

        assertEquals(2, result.size());
    }

    /**
     * issue #1057: /api/custom-tags?projectId= は非メンバーでも他プロジェクトのタグを読めていた
     * (対になる /api/projects/{projectId}/custom-tags は requireProjectMemberOrAdmin を経由するのに、
     * こちらには認可チェックが無かった)。projectId 指定時はプロジェクトメンバー判定を必須にする。
     */
    @Test
    void list_projectId指定時はプロジェクトメンバー判定を行う() {
        world.actingAsUser(5L).projectMember(PROJECT_ID, 5L, true);

        service.list(PROJECT_ID);

        assertEquals(1, world.http.requests("GET", "/api/internal/identity/projects/42/members/5").size());
    }

    @Test
    void list_プロジェクトメンバーでなければForbiddenExceptionが伝播する() {
        buildTag("p", PROJECT_ID);
        world.actingAsUser(5L).projectMember(PROJECT_ID, 5L, false);

        assertThrows(ForbiddenException.class, () -> service.list(PROJECT_ID));
    }

    /**
     * projectId 未指定(グローバルタグのみ)は、CustomTagService#list の Javadoc が明示する通り
     * 誰でも参照できる範囲なので、プロジェクトメンバー判定を経由しない。
     */
    @Test
    void list_projectId未指定ならプロジェクトメンバー判定を行わない() {
        world.actingAsUser(5L);

        service.list(null);

        assertTrue(world.http.requests("GET", "/api/internal/identity/projects/.*").isEmpty());
    }

    @Test
    void listByProject_グローバルタグを含まずプロジェクト固有のみ() {
        buildTag("g", null);
        buildTag("p", PROJECT_ID);

        List<CustomTagResponse> result = service.listByProject(PROJECT_ID);

        assertEquals(1, result.size());
        assertEquals("p", result.get(0).tagName());
    }

    // ------------------------------------------------------------------
    // previewCss / applySelectorPrefix / prefixSelectorList
    // ------------------------------------------------------------------

    private String previewWithPrefix(String css) {
        world.settingsFor(PROJECT_ID, PREFIX);
        return service.previewCss(css, PROJECT_ID);
    }

    @Test
    void previewCss_単純なセレクタにプリフィックスが付与される() {
        String result = previewWithPrefix(".card { color: red; }");

        assertEquals(".myproj .card { color: red; }", result);
    }

    @Test
    void previewCss_カンマ区切りの複数セレクタそれぞれにプリフィックスが付与される() {
        String result = previewWithPrefix(".card, .box { color: red; }");

        assertEquals(".myproj .card, .myproj .box { color: red; }", result);
    }

    @Test
    void previewCss_改行なしで連結された複数ルールもそれぞれ処理される() {
        // issue #307: 組み込みタグのデザインCSS等、改行なしで連結されたルール。
        // プリフィックス付与後は常に `{` の直前に半角スペース1つが挿入される(元のCSSに`{`前の
        // スペースが無くても)。
        String result = previewWithPrefix(".a{color:red;}.b{color:blue;}");

        assertEquals(".myproj .a {color:red;}.myproj .b {color:blue;}", result);
    }

    @Test
    void previewCss_複数行にまたがるセレクタもプリフィックスが付与される() {
        String result = previewWithPrefix(".a,\n.b {\n  color: red;\n}");

        String expectedSelector = ".myproj .a, .myproj .b";
        assertTrue(result.startsWith(expectedSelector + " {"),
                "実際の出力: " + result);
    }

    @Test
    void previewCss_擬似クラス内の丸括弧の中のカンマではセレクタを分割しない() {
        String result = previewWithPrefix(".card:not(.a, .b), .box { color: red; }");

        assertEquals(".myproj .card:not(.a, .b), .myproj .box { color: red; }", result);
    }

    @Test
    void previewCss_ネストした丸括弧を含むセレクタでも正しく分割する() {
        // :is()の中にさらに:not(...)がネストしているケース
        String result = previewWithPrefix(".card:is(.a, :not(.b, .c)), .box { color: red; }");

        assertEquals(".myproj .card:is(.a, :not(.b, .c)), .myproj .box { color: red; }", result);
    }

    @Test
    void previewCss_独立したブロックコメントはそのまま出力されプリフィックス対象にならない() {
        // 独立したブロックコメント自体は改行を含めそのまま出力されるが、コメント直後から次の
        // セレクタ開始までの間にある空白/改行は、セレクタテキストの一部としてstrip()されるため
        // 出力には残らない(下記のprefixSelectorList実装に依存する挙動)。
        String result = previewWithPrefix("/* header comment */\n.card { color: red; }");

        assertEquals("/* header comment */.myproj .card { color: red; }", result);
    }

    @Test
    void previewCss_ルール間のコメントはそのまま保持され後続セレクタの解析に影響しない() {
        String result = previewWithPrefix(".a { color: red; }\n/* note */\n.b { color: blue; }");

        // 直前のルールの`}`とコメントの間の改行は保持されるが、コメントと後続セレクタの間の改行は
        // 上記と同じ理由(セレクタテキストのstrip())で失われる。
        assertEquals(".myproj .a { color: red; }\n/* note */.myproj .b { color: blue; }", result);
    }

    @Test
    void previewCss_コメント内の波括弧は波括弧の深さのカウントに影響しない() {
        String result = previewWithPrefix("/* fake { selector } */\n.card { color: red; }");

        assertEquals("/* fake { selector } */.myproj .card { color: red; }", result);
    }

    @Test
    void previewCss_宣言値の文字列リテラル内の波括弧は波括弧の深さのカウントに影響しない() {
        String result = previewWithPrefix(".card::before { content: \"{ not a selector }\"; }\n.after { color: blue; }");

        assertEquals(
                ".myproj .card::before { content: \"{ not a selector }\"; }.myproj .after { color: blue; }",
                result);
    }

    @Test
    void previewCss_文字列リテラル内のエスケープされた引用符は文字列の終端と誤認しない() {
        String result = previewWithPrefix(".card::before { content: \"say \\\"hi\\\"\"; }\n.after { color: blue; }");

        assertEquals(
                ".myproj .card::before { content: \"say \\\"hi\\\"\"; }.myproj .after { color: blue; }",
                result);
    }

    @Test
    void previewCss_シングルクォート文字列内の波括弧も同様に無視される() {
        String result = previewWithPrefix(".card::before { content: '{ not a selector }'; }\n.after { color: blue; }");

        assertEquals(
                ".myproj .card::before { content: '{ not a selector }'; }.myproj .after { color: blue; }",
                result);
    }

    @Test
    void previewCss_mediaクエリ内のネストしたセレクタにもプリフィックスが付与される() {
        String result = previewWithPrefix("@media (max-width: 600px) { .card { color: red; } }");

        assertEquals("@media (max-width: 600px) {.myproj .card { color: red; } }", result);
    }

    @Test
    void previewCss_keyframesの中身はセレクタではないためプリフィックスを付与しない() {
        String result = previewWithPrefix("@keyframes spin { from { opacity: 0; } to { opacity: 1; } }");

        assertEquals("@keyframes spin { from { opacity: 0; } to { opacity: 1; } }", result);
    }

    @Test
    void previewCss_ベンダープレフィックス付きkeyframesも対象外になる() {
        String result = previewWithPrefix("@-webkit-keyframes spin { from { opacity: 0; } to { opacity: 1; } }");

        assertEquals("@-webkit-keyframes spin { from { opacity: 0; } to { opacity: 1; } }", result);
    }

    @Test
    void previewCss_fontFaceの中身はプリフィックス対象外になる() {
        String result = previewWithPrefix("@font-face { font-family: \"MyFont\"; src: url(a.woff); }");

        assertEquals("@font-face { font-family: \"MyFont\"; src: url(a.woff); }", result);
    }

    @Test
    void previewCss_keyframes後の通常セレクタにはプリフィックスが付与される() {
        String result = previewWithPrefix(
                "@keyframes spin { from { opacity: 0; } to { opacity: 1; } } .card { color: red; }");

        assertEquals(
                "@keyframes spin { from { opacity: 0; } to { opacity: 1; } }.myproj .card { color: red; }",
                result);
    }

    @Test
    void previewCss_空文字は空文字のまま返す() {
        assertEquals("", previewWithPrefix(""));
    }

    @Test
    void previewCss_nullは空文字を返す() {
        assertEquals("", previewWithPrefix(null));
    }

    @Test
    void previewCss_プレフィックス未解決nullの場合はCSSをそのまま返す() {
        // 設定の行が無く、project-serviceもslugを持たない
        String css = ".card { color: red; }";
        assertEquals(css, service.previewCss(css, PROJECT_ID));
    }

    @Test
    void previewCss_プレフィックスが空白のみの場合はCSSをそのまま返す() {
        world.projectSlug(PROJECT_ID, "   ");

        String css = ".card { color: red; }";
        assertEquals(css, service.previewCss(css, PROJECT_ID));
    }

    @Test
    void previewCss_設定が無ければproject_serviceのslugをプリフィックスにする() {
        world.projectSlug(PROJECT_ID, "slug-x");

        assertEquals(".slug-x .card { color: red; }", service.previewCss(".card { color: red; }", PROJECT_ID));
        assertEquals("Bearer token-1",
                world.http.requests("GET", "/api/internal/project/projects/42").get(0).header("Authorization"));
    }

    /**
     * 既知の制限事項の記録用テスト(issue #656調査時に発見): prefixSelectorListは丸括弧の深さのみを
     * 追跡しており、引用符で囲まれた文字列は認識しない。そのため属性セレクタの値にカンマを含む
     * (`[data-x="a, b"]`のような)セレクタは、本来分割すべきでない箇所でカンマ分割されてしまう。
     * このテストは「あるべき正しい動作」ではなく、現状の実装が実際にどう振る舞うかを固定して記録する
     * ためのものであり、production側は変更していない(詳細はPRの説明を参照)。
     */
    @Test
    void previewCss_既知の制限属性セレクタ内のカンマは意図せず分割される() {
        String result = previewWithPrefix(".card[data-x=\"a, b\"] { color: red; }");

        // 期待される「正しい」出力は ".myproj .card[data-x=\"a, b\"] { color: red; }" だが、
        // 実際にはカンマがトップレベルの区切りとして扱われ2つのセレクタに分割される。
        assertEquals(".myproj .card[data-x=\"a, .myproj b\"] { color: red; }", result);
        assertFalse(result.contains(".myproj .card[data-x=\"a, b\"]"));
    }

    // ---- issue #1821: CSS走査の分岐 ----

    @Test
    void previewCss_セレクタの途中にあるコメントはセレクタの一部として扱う() {
        assertEquals(".myproj .a /* c * d */ {color:red;}", previewWithPrefix(".a /* c * d */ {color:red;}"));
    }

    @Test
    void previewCss_閉じていないコメントは最後までコメントとして残す() {
        assertEquals(".a /* open *", previewWithPrefix(".a /* open *"));
        assertEquals("/* open * ", previewWithPrefix("/* open * "));
        assertEquals("/* a * b */", previewWithPrefix("/* a * b */"));
        assertEquals("/* x *", previewWithPrefix("/* x *"));
    }

    @Test
    void previewCss_文字列の最後が円記号でも走査を終える() {
        assertEquals(".myproj .a::before {content: \"x\\", previewWithPrefix(".a::before {content: \"x\\"));
    }

    @Test
    void previewCss_スラッシュだけの文字はコメントの開始とみなさない() {
        assertEquals(".myproj .a {background: url(a/b.png);}/",
                previewWithPrefix(".a {background: url(a/b.png);}/"));
    }

    @Test
    void previewCss_宣言ブロックの中の入れ子の波括弧と空のセレクタにはプリフィックスを付けない() {
        assertEquals("{ color: red; }", previewWithPrefix("{ color: red; }"));
        assertEquals(".myproj .a { .b { color: red; } }", previewWithPrefix(".a { .b { color: red; } }"));
        assertEquals(".myproj .a { @media (x) { color: red; } }",
                previewWithPrefix(".a { @media (x) { color: red; } }"));
    }

    @Test
    void previewCss_at_ruleの入れ子はメディアクエリの中だけセレクタとして扱い_keyframesの中は扱わない() {
        assertEquals("@media print {@media screen {.myproj .a {color:red;}}}",
                previewWithPrefix("@media print {@media screen {.a {color:red;}}}"));
        assertEquals("@media print {@page {margin:0;}}", previewWithPrefix("@media print {@page {margin:0;}}"));
        assertEquals("@media print {@keyframes k {from {opacity:0;}}.myproj .b {color:red;}}",
                previewWithPrefix("@media print {@keyframes k {from {opacity:0;}}.b {color:red;}}"));
    }

    @Test
    void previewCss_対応しない閉じ括弧があっても走査を続ける() {
        assertEquals(".myproj .a {color:red;}}.myproj .b {color:blue;}",
                previewWithPrefix(".a {color:red;}}.b {color:blue;}"));
    }

    @Test
    void previewCss_空のセレクタがカンマで挟まれていれば取り除く() {
        assertEquals(".myproj .a, .myproj .b {color:red;}", previewWithPrefix(".a,, .b, {color:red;}"));
    }

    // ------------------------------------------------------------------
    // buildCssBundle / buildProjectCssBundle
    // ------------------------------------------------------------------

    private void assertEmbedTagCssIncluded(String result) {
        // 組み込みタグ([toc]/[blogcard]/[amazon])のデザインCSSが、プリフィックス付きで先頭に入る
        assertTrue(result.contains("/* === toc (組み込みタグ) === */.myproj .lb-toc-list {list-style:disc;"), result);
        assertTrue(result.contains("/* === blogcard (組み込みタグ) === */.myproj .lb-blogcard {"), result);
        assertTrue(result.contains("/* === amazon (組み込みタグ) === */.myproj .lb-amazon-card {"), result);
        assertTrue(result.indexOf("=== toc") < result.indexOf("=== blogcard")
                && result.indexOf("=== blogcard") < result.indexOf("=== amazon"));
        for (String type : new String[] {"TOC", "BLOGCARD", "AMAZON"}) {
            var request = world.http.requests("GET", "/api/internal/project/tag-design/" + type).get(0);
            assertEquals("projectId=42", request.query());
            assertEquals("Bearer token-1", request.header("Authorization"));
        }
    }

    @Test
    void buildCssBundle_projectId未指定ならグローバルタグのみで組み込みタグCSSは含まない() {
        CustomTag tag = buildTag("note", null);
        tag.setCssContent(".note { color: red; }");

        String result = service.buildCssBundle(null);

        assertTrue(result.contains(".note { color: red; }"));
        assertFalse(result.contains("lb-toc"));
        assertTrue(world.http.requests("GET", "/api/internal/project/.*").isEmpty());
    }

    @Test
    void buildCssBundle_projectId指定時は組み込みタグCSSとプロジェクト固有タグとグローバルタグを結合する() {
        world.settingsFor(PROJECT_ID, PREFIX);
        buildTag("g", null).setCssContent(".g { color: red; }");
        buildTag("p", PROJECT_ID).setCssContent(".p { color: blue; }");

        String result = service.buildCssBundle(PROJECT_ID);

        assertEmbedTagCssIncluded(result);
        assertTrue(result.contains(".myproj .g { color: red; }"));
        assertTrue(result.contains(".myproj .p { color: blue; }"));
    }

    @Test
    void buildCssBundle_デザインのcustomCssがあれば組み込みタグCSSとしてそれを使う() {
        world.settingsFor(PROJECT_ID, PREFIX);
        world.tagDesign("TOC", ".mine { color: red; }", null);

        String result = service.buildCssBundle(PROJECT_ID);

        assertTrue(result.contains("/* === toc (組み込みタグ) === */.myproj .mine { color: red; }"), result);
    }

    /**
     * issue #1057: /api/custom-tags/css-bundle?projectId= も list() と同じ抜けを持っていた。
     */
    @Test
    void buildCssBundle_projectId指定時はプロジェクトメンバー判定を行う() {
        world.settingsFor(PROJECT_ID, PREFIX);
        world.actingAsUser(5L).projectMember(PROJECT_ID, 5L, true);

        service.buildCssBundle(PROJECT_ID);

        assertEquals(1, world.http.requests("GET", "/api/internal/identity/projects/42/members/5").size());
    }

    @Test
    void buildCssBundle_プロジェクトメンバーでなければForbiddenExceptionが伝播する() {
        world.actingAsUser(5L).projectMember(PROJECT_ID, 5L, false);

        assertThrows(ForbiddenException.class, () -> service.buildCssBundle(PROJECT_ID));
        assertTrue(world.http.requests("GET", "/api/internal/project/tag-design/.*").isEmpty());
    }

    @Test
    void buildCssBundle_projectId未指定ならプロジェクトメンバー判定を行わない() {
        world.actingAsUser(5L);

        service.buildCssBundle(null);

        assertTrue(world.http.requests("GET", "/api/internal/identity/projects/.*").isEmpty());
    }

    @Test
    void buildCssBundle_cssContentが空のタグは連結対象から除外される() {
        buildTag("blank", null).setCssContent("   ");
        buildTag("nothing", null);
        buildTag("note", null).setCssContent(".note { color: red; }");

        String result = service.buildCssBundle(null);

        assertFalse(result.contains("=== blank ==="));
        assertFalse(result.contains("=== nothing ==="));
        assertTrue(result.contains("=== note ==="));
    }

    @Test
    void buildProjectCssBundle_グローバルタグを含まずプロジェクト固有タグと組み込みタグCSSのみ() {
        world.settingsFor(PROJECT_ID, PREFIX);
        buildTag("g", null).setCssContent(".g { color: red; }");
        buildTag("p", PROJECT_ID).setCssContent(".p { color: blue; }");

        String result = service.buildProjectCssBundle(PROJECT_ID);

        assertEmbedTagCssIncluded(result);
        assertTrue(result.contains(".myproj .p { color: blue; }"));
        assertFalse(result.contains(".g { color: red; }"));
    }
}
