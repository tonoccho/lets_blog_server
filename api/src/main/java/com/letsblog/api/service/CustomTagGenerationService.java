package com.letsblog.api.service;

import com.letsblog.api.ai.OllamaClient;
import com.letsblog.api.ai.PenpotClient;
import com.letsblog.api.domain.CustomTag;
import com.letsblog.api.dto.GenerateCustomTagRequest;
import com.letsblog.api.dto.GenerateCustomTagResponse;
import com.letsblog.api.dto.ValidationError;
import com.letsblog.api.dto.ValidationResult;
import com.letsblog.api.repository.CustomTagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CustomTagGenerationService {

    private static final Logger log = LoggerFactory.getLogger(CustomTagGenerationService.class);

    private final OllamaClient ollamaClient;
    private final PenpotClient penpotClient;
    private final CustomTagRepository customTagRepository;
    private final AdminAuthorizationService adminAuthorizationService;
    private final CustomTagValidationService customTagValidationService;

    private static final Pattern HTML_PATTERN = Pattern.compile("```html\\s*\\n([\\s\\S]*?)\\n```");
    private static final Pattern CSS_PATTERN = Pattern.compile("```css\\s*\\n([\\s\\S]*?)\\n```");

    public CustomTagGenerationService(
            OllamaClient ollamaClient,
            PenpotClient penpotClient,
            CustomTagRepository customTagRepository,
            AdminAuthorizationService adminAuthorizationService,
            CustomTagValidationService customTagValidationService) {
        this.ollamaClient = ollamaClient;
        this.penpotClient = penpotClient;
        this.customTagRepository = customTagRepository;
        this.adminAuthorizationService = adminAuthorizationService;
        this.customTagValidationService = customTagValidationService;
    }

    @Transactional
    public GenerateCustomTagResponse generate(GenerateCustomTagRequest request) {
        adminAuthorizationService.requireAdmin();

        // プロンプトをOllamaに送信
        String ollamaResponse = ollamaClient.generate(buildPrompt(request.prompt()));

        // HTMLとCSSを抽出
        String htmlTemplate = extractHtml(ollamaResponse);
        String cssContent = extractCss(ollamaResponse);

        // バリデーション
        if (htmlTemplate.isBlank()) {
            throw new InvalidCustomTagContentException(
                    "OllamaレスポンスからHTMLを抽出できませんでした。```html ... ``` の形式で返されることを確認してください。");
        }

        ValidationResult validationResult = customTagValidationService.validate(htmlTemplate, cssContent);
        if (!validationResult.isValid()) {
            String errorMessage = validationResult.errors().stream()
                    .map(ValidationError::message)
                    .collect(Collectors.joining(", "));
            throw new InvalidCustomTagContentException("生成されたHTML/CSSがセキュリティ要件を満たしていません: " + errorMessage);
        }

        // カスタムタグが既に存在するかチェック
        Long projectId = request.projectId();
        findDuplicate(request.tagName(), projectId).ifPresent(existing -> {
            throw new IllegalArgumentException("タグ名 '" + request.tagName() + "' は既に登録されています");
        });

        // カスタムタグを作成・保存
        CustomTag tag = new CustomTag();
        tag.setTagName(request.tagName());
        tag.setHtmlTemplate(htmlTemplate);
        tag.setCssContent(cssContent);
        tag.setDescription(request.description());
        tag.setProjectId(projectId);
        tag.setPenpotFileUrl(tryCreatePenpotDesignFile(request.tagName(), request.prompt(), htmlTemplate, cssContent));

        CustomTag savedTag = customTagRepository.save(tag);
        return GenerateCustomTagResponse.from(savedTag);
    }

    /**
     * Ollamaへ送ったプロンプトと生成結果を元に、Penpot上へハンドオフ用のデザインファイルを作成する。
     * Penpotへの接続失敗はカスタムタグ生成そのものを失敗させないベストエフォート扱いとする
     * (Penpotが未起動/未設定でもタグ生成というコア機能は継続できるべきため)。
     */
    private String tryCreatePenpotDesignFile(String tagName, String prompt, String htmlTemplate, String cssContent) {
        try {
            String promptContext = "Ollamaへのプロンプト:\n" + prompt
                    + "\n\n生成されたHTML:\n" + htmlTemplate
                    + "\n\n生成されたCSS:\n" + cssContent;
            PenpotClient.DesignFile designFile =
                    penpotClient.createDesignFile("カスタムタグ: " + tagName, promptContext);
            return designFile.url();
        } catch (RuntimeException e) {
            log.warn("Penpotデザインファイルの作成に失敗しました(タグ生成は継続します): {}", e.getMessage());
            return null;
        }
    }

    private String buildPrompt(String userPrompt) {
        return String.format(
            """
            ユーザーの要求に基づいて、HTMLコンポーネントとそのCSSスタイルを生成してください。

            要件:
            1. HTMLは```html...```で囲まれた形式で出力してください
            2. CSSは```css...```で囲まれた形式で出力してください
            3. HTMLはシンプルで拡張可能な構造を心がけてください
            4. CSSは適切なクラス命名(BEM形式推奨)を使用してください
            5. レスポンシブデザインに対応してください

            ユーザーのリクエスト:
            %s
            """,
            userPrompt
        );
    }

    private String extractHtml(String response) {
        Matcher matcher = HTML_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).strip();
        }
        return "";
    }

    private String extractCss(String response) {
        Matcher matcher = CSS_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).strip();
        }
        return "";
    }

    private Optional<CustomTag> findDuplicate(String tagName, Long projectId) {
        return projectId == null
                ? customTagRepository.findByTagNameAndProjectIdIsNull(tagName)
                : customTagRepository.findByTagNameAndProjectId(tagName, projectId);
    }
}
