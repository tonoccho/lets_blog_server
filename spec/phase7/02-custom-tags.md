# 02. カスタムショートコードタグ機能

## 目的

現在の`MarkdownRenderer`はflexmark-javaの`TablesExtension`のみを有効化した最小構成で、独自記法を追加する仕組みがない。管理画面上でショートコード的なタグ(タグ名とHTMLテンプレートの組)をDB駆動で定義・編集でき、投稿のMarkdown本文中でそのタグを使うと対応するHTMLに変換される機能を追加する。

## 前提・決定事項

- 記法: フェンス形式 `:::tagname key="value" key2="value2"\n本文\n:::`。PlantUMLの ` ```plantuml ` (バッククォート3連)とは別の記法体系(コロン3連)にすることで、タグ名の予約語衝突判定を実装せずに構造的に回避する。
- スコープ: 全サイト共通のグローバル機能(サイト単位のスコープは設けない。PlantUML埋め込みと同じ扱い)。
- テンプレート内プレースホルダ: `{{content}}`(フェンス内本文、Markdownとして解釈させたい場合は本文をそのまま埋め込む)、`{{attr:key}}`(属性値、存在しない場合は空文字)。
- 実装方式: flexmarkの拡張機構(NodeParser/HtmlNodeRenderer)は使わず、既存の`PlantUmlEmbedService`と同型の**正規表現による前処理**(Markdown文字列→Markdown/HTML文字列の置換)とする。既存コードとの一貫性を優先。
- 権限: 作成・更新・削除はadmin権限限定(`AdminAuthorizationService.requireAdmin()`)。一覧取得は認証済みユーザーであれば可能。
- テンプレートのHTML許可: サニタイズは行わない。admin権限者のみが編集できるため、投稿者側の信頼境界と同列に扱う。

## コンポーネント構成

### `V9__add_custom_tags.sql` (新規Flywayマイグレーション)

```sql
CREATE TABLE custom_tags (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tag_name VARCHAR(100) NOT NULL UNIQUE,
    html_template TEXT NOT NULL,
    description VARCHAR(500),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
```

### `CustomTag.java` (domain, 新規)

`id` / `tagName` / `htmlTemplate` / `description` / `createdAt` / `updatedAt`。既存の`MailTemplate.java`と同様の`@Entity`構成(`@PrePersist`/`@PreUpdate`で日時セット)。

### `CustomTagRepository.java` (新規)

`findByTagName(String tagName)` / `findAll()` を持つ`JpaRepository<CustomTag, Long>`。

### `CustomTagRequest.java` / `CustomTagResponse.java` (dto, 新規)

```java
public record CustomTagRequest(
        @NotBlank @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_-]*$") String tagName,
        @NotBlank String htmlTemplate,
        String description
) {}

public record CustomTagResponse(
        Long id, String tagName, String htmlTemplate, String description,
        LocalDateTime createdAt, LocalDateTime updatedAt
) {
    public static CustomTagResponse from(CustomTag tag) { ... }
}
```

`tagName`のバリデーションで英数字・ハイフン・アンダースコアのみに制限する(正規表現の特殊文字を含む記法上のトラブルを避けるため)。

### `CustomTagService.java` (新規)

- `create(CustomTagRequest)`: admin権限チェック→`tag_name`重複チェック→保存
- `update(Long id, CustomTagRequest)`: admin権限チェック→存在チェック→更新
- `delete(Long id)`: admin権限チェック→削除
- `list()`: 全件取得(権限チェックなし、投稿画面等での参照用)
- `findAllAsMap()`: `CustomTagRenderService`が使う、`tagName → CustomTag`のMap(パフォーマンスのため一括取得)

### `CustomTagNotFoundException.java` (新規、`SiteNotFoundException`等と同型)

### `CustomTagController.java` (新規)

```java
@RestController
@RequestMapping("/api/custom-tags")
public class CustomTagController {

    @PostMapping
    public ResponseEntity<CustomTagResponse> create(@Valid @RequestBody CustomTagRequest request) { ... }

    @GetMapping
    public List<CustomTagResponse> list() { ... }

    @PutMapping("/{id}")
    public CustomTagResponse update(@PathVariable Long id, @Valid @RequestBody CustomTagRequest request) { ... }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) { ... }
}
```

### `CustomTagRenderService.java` (新規、`PlantUmlEmbedService`と同型)

```java
@Service
public class CustomTagRenderService {

    private static final Pattern CUSTOM_TAG_PATTERN =
            Pattern.compile(":::([a-zA-Z][a-zA-Z0-9_-]*)([^\\n]*)\\n(.*?):::", Pattern.DOTALL);
    private static final Pattern ATTR_PATTERN =
            Pattern.compile("([a-zA-Z][a-zA-Z0-9_-]*)=\"([^\"]*)\"");

    private final CustomTagRepository customTagRepository;

    public String render(String markdown) {
        Map<String, CustomTag> tagsByName = customTagRepository.findAll().stream()
                .collect(Collectors.toMap(CustomTag::getTagName, t -> t));

        Matcher matcher = CUSTOM_TAG_PATTERN.matcher(markdown);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String tagName = matcher.group(1);
            String attrPart = matcher.group(2);
            String content = matcher.group(3);

            CustomTag tag = tagsByName.get(tagName);
            String replacement;
            if (tag == null) {
                // 未定義タグはそのまま残す(元のブロック文字列を維持)
                replacement = matcher.group(0);
            } else {
                replacement = applyTemplate(tag.getHtmlTemplate(), content, parseAttrs(attrPart));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String applyTemplate(String template, String content, Map<String, String> attrs) {
        String out = template.replace("{{content}}", content.trim());
        Matcher am = Pattern.compile("\\{\\{attr:([a-zA-Z0-9_-]+)}}").matcher(out);
        StringBuilder sb = new StringBuilder();
        while (am.find()) {
            am.appendReplacement(sb, Matcher.quoteReplacement(attrs.getOrDefault(am.group(1), "")));
        }
        am.appendTail(sb);
        return sb.toString();
    }

    private Map<String, String> parseAttrs(String attrPart) {
        Map<String, String> attrs = new LinkedHashMap<>();
        Matcher m = ATTR_PATTERN.matcher(attrPart == null ? "" : attrPart);
        while (m.find()) {
            attrs.put(m.group(1), m.group(2));
        }
        return attrs;
    }
}
```

未定義タグ名を書いた場合はブロックをそのまま残す(エラーにはしない)方針とする。理由: 投稿者が入力ミスをした際、投稿処理全体を失敗させるより、意図しない文字列がそのまま表示される方が実害が小さく、既存のPlantUML処理(該当ブロックが無ければ何もしない)とも挙動を揃えやすいため。

### `PostPublishService.java` (修正)

```java
public PostPublishService(SiteService siteService, CmsAdapterFactory cmsAdapterFactory,
                           MarkdownRenderer markdownRenderer, PostRepository postRepository,
                           PlantUmlEmbedService plantUmlEmbedService,
                           CustomTagRenderService customTagRenderService) {
    ...
    this.customTagRenderService = customTagRenderService;
}

public PostPublishResponse publish(PostPublishCommand command) {
    ...
    String markdown = customTagRenderService.render(command.markdown());
    markdown = plantUmlEmbedService.embedDiagrams(credentials, markdown);
    markdown = replaceImageReferences(cmsAdapter, credentials, markdown, command.images());
    String html = markdownRenderer.render(markdown);
    ...
}
```

カスタムタグ展開をPlantUML埋め込みより先に行う(カスタムタグのテンプレート内にPlantUMLフェンスブロックを含められるようにするため)。

### Web管理画面: `/custom-tags` (新規)

`web/src/app/custom-tags/page.tsx` + `CustomTagForm.tsx` + `actions.ts`(既存`sites/`と同様の3ファイル構成)。一覧表示・作成・編集・削除フォーム。admin権限がない場合はアクセス不可(既存の`admin/roles`等と同様のガード)。

## タスクチェックリスト

- [x] `V9__add_custom_tags.sql` 作成
- [x] `CustomTag.java` エンティティ実装
- [x] `CustomTagRepository.java` 実装
- [x] `CustomTagRequest.java` / `CustomTagResponse.java` 実装
- [x] `CustomTagNotFoundException.java` 実装、`GlobalExceptionHandler`に追加
- [x] `CustomTagService.java` 実装(CRUD、admin権限チェック)
- [x] `CustomTagController.java` 実装
- [x] `CustomTagRenderService.java` 実装
- [x] `PostPublishService.java` に組み込み
- [x] `CustomTagRenderServiceTest.java` 実装(タグ未定義時・属性あり/なし・複数タグ混在のケース)
- [x] `CustomTagServiceTest.java` 実装(admin権限チェック・重複チェック)
- [x] Web管理画面 `/custom-tags` ページ実装
- [x] `./gradlew test` で全テストPASS確認
- [x] 実機検証: 管理画面でタグ作成→投稿APIから`:::tagname`記法を含む投稿→WordPress側でHTML展開結果を確認

## 実装状況

計画通りに実装。実機検証(常駐WordPressコンテナへの実投稿)で`:::alert ... :::`ブロックが登録済みテンプレート通りのHTMLへ展開されることを確認した。ただし`{{content}}`に差し込まれる本文はMarkdownとして再解釈されない(flexmarkがHTMLブロックとして素通しするため、ブロック内の`**強調**`等は展開されずそのまま残る)ことを実機で確認した。これは設計時点で許容した仕様(本セクション冒頭のプレースホルダ説明を参照)であり、ネストしたMarkdown解釈が必要な場合は将来的な検討課題とする。

## 未決事項

- カスタムタグテンプレートのプレースホルダ仕様拡張(繰り返し・条件分岐等、将来必要になった場合)
- 未定義タグ使用時の挙動(現状は無視してそのまま残す方針だが、投稿画面側で警告表示する等のUX改善は将来検討)
- `{{content}}`部分をネストしたMarkdownとして再解釈させたい場合の対応(現状はHTMLブロックとして素通しされ非対応)
