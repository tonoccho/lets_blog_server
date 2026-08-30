package com.letsblog.content.service;

import com.letsblog.content.client.LegacyApiBridgeClient;
import com.letsblog.content.domain.CustomTag;
import com.letsblog.content.dto.CustomTagRequest;
import com.letsblog.content.dto.CustomTagResponse;
import com.letsblog.content.dto.TagDesignColors;
import com.letsblog.content.repository.CustomTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #656: #641のレビューで、CustomTag*サービス群のうちCustomTagServiceが未テストのまま
 * 残っていたことを受けて追加。特にapplySelectorPrefix/prefixSelectorList
 * (管理者が入力したCSSをWordPressへ一括貼り付けするために`.prefix `を付与する、コメント/文字列
 * リテラル/波括弧の深さ/丸括弧の深さを1パスで追跡する手書きパーサ、issue #298/#307)を、previewCss
 * (DB未保存のCSSに対しても同じ変換を行う公開メソッド、issue #335)経由で検証する。
 * previewCssはprivateなapplySelectorPrefix/prefixSelectorListの唯一のpublicな入口。
 */
@ExtendWith(MockitoExtension.class)
class CustomTagServiceTest {

    private static final Long PROJECT_ID = 42L;
    private static final String PREFIX = "myproj";

    @Mock
    private CustomTagRepository customTagRepository;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private LegacyApiBridgeClient legacyApiBridgeClient;
    @Mock
    private CurrentActorService currentActorService;
    @Mock
    private TocStyleRenderService tocStyleRenderService;
    @Mock
    private BlogCardTagRenderService blogCardTagRenderService;
    @Mock
    private AmazonTagRenderService amazonTagRenderService;
    @Mock
    private ProjectContentSettingsService projectContentSettingsService;

    private CustomTagService service;

    @BeforeEach
    void setUp() {
        service = new CustomTagService(
                customTagRepository,
                adminAuthorizationService,
                legacyApiBridgeClient,
                currentActorService,
                tocStyleRenderService,
                blogCardTagRenderService,
                amazonTagRenderService,
                projectContentSettingsService);
    }

    private CustomTagRequest buildRequest(String tagName, Long projectId) {
        return new CustomTagRequest(tagName, "<div>{{content}}</div>", "desc", ".card{color:red;}", null, projectId);
    }

    private CustomTag buildTag(Long id, String tagName, Long projectId) {
        CustomTag tag = new CustomTag();
        tag.setId(id);
        tag.setTagName(tagName);
        tag.setProjectId(projectId);
        return tag;
    }

    // ------------------------------------------------------------------
    // create / update / delete / list / listByProject
    // ------------------------------------------------------------------

    @Test
    void create_タグ名が重複していなければ保存する() {
        when(customTagRepository.findByTagNameAndProjectIdIsNull("note")).thenReturn(Optional.empty());
        when(customTagRepository.save(any(CustomTag.class))).thenAnswer(invocation -> {
            CustomTag tag = invocation.getArgument(0);
            tag.setId(1L);
            return tag;
        });

        CustomTagResponse response = service.create(buildRequest("note", null));

        assertEquals(1L, response.id());
        assertEquals("note", response.tagName());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void create_同じタグ名が既に存在する場合はIllegalArgumentException() {
        when(customTagRepository.findByTagNameAndProjectIdIsNull("note"))
                .thenReturn(Optional.of(buildTag(1L, "note", null)));

        assertThrows(IllegalArgumentException.class, () -> service.create(buildRequest("note", null)));
        verify(customTagRepository, never()).save(any());
    }

    @Test
    void create_admin権限が無ければForbiddenExceptionが伝播し保存されない() {
        doThrow(new ForbiddenException("no admin")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> service.create(buildRequest("note", null)));
        verify(customTagRepository, never()).save(any());
    }

    @Test
    void update_存在するタグを更新する() {
        CustomTag existing = buildTag(1L, "old-name", null);
        when(customTagRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(customTagRepository.findByTagNameAndProjectIdIsNull("note")).thenReturn(Optional.empty());
        when(customTagRepository.save(any(CustomTag.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomTagResponse response = service.update(1L, buildRequest("note", 999L));

        // projectIdの変更は無視される(既存タグのprojectIdのまま)
        assertEquals(null, response.projectId());
        assertEquals("note", response.tagName());
    }

    @Test
    void update_存在しないidはCustomTagNotFoundException() {
        when(customTagRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(CustomTagNotFoundException.class, () -> service.update(99L, buildRequest("note", null)));
    }

    @Test
    void update_自分自身以外が同じタグ名を使っている場合はIllegalArgumentException() {
        CustomTag existing = buildTag(1L, "old-name", null);
        when(customTagRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(customTagRepository.findByTagNameAndProjectIdIsNull("note"))
                .thenReturn(Optional.of(buildTag(2L, "note", null)));

        assertThrows(IllegalArgumentException.class, () -> service.update(1L, buildRequest("note", null)));
    }

    @Test
    void update_自分自身と同じidが同じタグ名でヒットしても許可される() {
        CustomTag existing = buildTag(1L, "note", null);
        when(customTagRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(customTagRepository.findByTagNameAndProjectIdIsNull("note")).thenReturn(Optional.of(existing));
        when(customTagRepository.save(any(CustomTag.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomTagResponse response = service.update(1L, buildRequest("note", null));

        assertEquals("note", response.tagName());
    }

    @Test
    void delete_存在するタグを削除する() {
        when(customTagRepository.existsById(1L)).thenReturn(true);

        service.delete(1L);

        verify(customTagRepository).deleteById(1L);
    }

    @Test
    void delete_存在しないidはCustomTagNotFoundException() {
        when(customTagRepository.existsById(99L)).thenReturn(false);

        assertThrows(CustomTagNotFoundException.class, () -> service.delete(99L));
        verify(customTagRepository, never()).deleteById(any());
    }

    @Test
    void list_projectId未指定ならグローバルタグのみ() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(List.of(buildTag(1L, "g", null)));

        List<CustomTagResponse> result = service.list(null);

        assertEquals(1, result.size());
        assertEquals("g", result.get(0).tagName());
    }

    @Test
    void list_projectId指定時はプロジェクト固有とグローバルの両方() {
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(PROJECT_ID))
                .thenReturn(List.of(buildTag(1L, "g", null), buildTag(2L, "p", PROJECT_ID)));

        List<CustomTagResponse> result = service.list(PROJECT_ID);

        assertEquals(2, result.size());
    }

    @Test
    void listByProject_グローバルタグを含まずプロジェクト固有のみ() {
        when(customTagRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(buildTag(2L, "p", PROJECT_ID)));

        List<CustomTagResponse> result = service.listByProject(PROJECT_ID);

        assertEquals(1, result.size());
        assertEquals("p", result.get(0).tagName());
    }

    // ------------------------------------------------------------------
    // previewCss / applySelectorPrefix / prefixSelectorList
    // ------------------------------------------------------------------

    private String previewWithPrefix(String css) {
        when(projectContentSettingsService.resolveCssSelectorPrefix(PROJECT_ID)).thenReturn(PREFIX);
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
        when(projectContentSettingsService.resolveCssSelectorPrefix(PROJECT_ID)).thenReturn(null);

        String css = ".card { color: red; }";
        assertEquals(css, service.previewCss(css, PROJECT_ID));
    }

    @Test
    void previewCss_プレフィックスが空白のみの場合はCSSをそのまま返す() {
        when(projectContentSettingsService.resolveCssSelectorPrefix(PROJECT_ID)).thenReturn("   ");

        String css = ".card { color: red; }";
        assertEquals(css, service.previewCss(css, PROJECT_ID));
    }

    @Test
    void previewCss_resolveCssSelectorPrefixにprojectIdを渡して解決する() {
        previewWithPrefix(".card { color: red; }");

        verify(projectContentSettingsService).resolveCssSelectorPrefix(PROJECT_ID);
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

    // ------------------------------------------------------------------
    // buildCssBundle / buildProjectCssBundle
    // ------------------------------------------------------------------

    private void stubEmbedTagCss() {
        when(legacyApiBridgeClient.resolveTagDesign(eq(PROJECT_ID), any(), any()))
                .thenReturn(new LegacyApiBridgeClient.TagDesignResponse("#fff", "#000", "#00f", null, null));
        when(legacyApiBridgeClient.toColors(any())).thenReturn(new TagDesignColors("#fff", "#000", "#00f", null));
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer token");
        when(tocStyleRenderService.buildStyle(any())).thenReturn(".lb-toc{color:red;}");
        when(blogCardTagRenderService.buildStyle(any())).thenReturn(".lb-blogcard{color:blue;}");
        when(amazonTagRenderService.buildStyle(any())).thenReturn(".lb-amazon{color:green;}");
    }

    @Test
    void buildCssBundle_projectId未指定ならグローバルタグのみで組み込みタグCSSは含まない() {
        CustomTag tag = buildTag(1L, "note", null);
        tag.setCssContent(".note { color: red; }");
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(List.of(tag));

        String result = service.buildCssBundle(null);

        assertTrue(result.contains(".note { color: red; }"));
        assertFalse(result.contains("lb-toc"));
        verify(projectContentSettingsService, never()).resolveCssSelectorPrefix(any());
    }

    @Test
    void buildCssBundle_projectId指定時は組み込みタグCSSとプロジェクト固有タグとグローバルタグを結合する() {
        stubEmbedTagCss();
        when(projectContentSettingsService.resolveCssSelectorPrefix(PROJECT_ID)).thenReturn(PREFIX);
        CustomTag globalTag = buildTag(1L, "g", null);
        globalTag.setCssContent(".g { color: red; }");
        CustomTag projectTag = buildTag(2L, "p", PROJECT_ID);
        projectTag.setCssContent(".p { color: blue; }");
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(PROJECT_ID))
                .thenReturn(List.of(globalTag, projectTag));

        String result = service.buildCssBundle(PROJECT_ID);

        assertTrue(result.contains(".myproj .lb-toc {color:red;}"), "実際の出力: " + result);
        assertTrue(result.contains(".myproj .g { color: red; }"));
        assertTrue(result.contains(".myproj .p { color: blue; }"));
    }

    @Test
    void buildCssBundle_cssContentが空のタグは連結対象から除外される() {
        CustomTag blank = buildTag(1L, "blank", null);
        blank.setCssContent("   ");
        CustomTag withCss = buildTag(2L, "note", null);
        withCss.setCssContent(".note { color: red; }");
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(List.of(blank, withCss));

        String result = service.buildCssBundle(null);

        assertFalse(result.contains("=== blank ==="));
        assertTrue(result.contains("=== note ==="));
    }

    @Test
    void buildProjectCssBundle_グローバルタグを含まずプロジェクト固有タグと組み込みタグCSSのみ() {
        stubEmbedTagCss();
        when(projectContentSettingsService.resolveCssSelectorPrefix(PROJECT_ID)).thenReturn(PREFIX);
        CustomTag projectTag = buildTag(2L, "p", PROJECT_ID);
        projectTag.setCssContent(".p { color: blue; }");
        when(customTagRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(projectTag));

        String result = service.buildProjectCssBundle(PROJECT_ID);

        assertTrue(result.contains(".myproj .lb-toc {color:red;}"));
        assertTrue(result.contains(".myproj .p { color: blue; }"));
        verify(customTagRepository, never()).findByProjectIdOrProjectIdIsNull(any());
    }
}
