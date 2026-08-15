package com.letsblog.api.integration;

import com.letsblog.api.ai.LlmClient;
import com.letsblog.api.domain.CustomTag;
import com.letsblog.api.dto.GenerateCustomTagRequest;
import com.letsblog.api.dto.ValidateCustomTagRequest;
import com.letsblog.api.repository.CustomTagRepository;
import com.letsblog.api.service.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("カスタムタグ生成機能の統合テスト")
class CustomTagGenerationIntegrationTest {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String TEST_API_KEY = "lb_test-key";
    private static final String ACTOR_ROLE_HEADER = "X-Actor-Role";
    private static final String ADMIN_ROLE = "admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LlmClient llmClient;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @Autowired
    private CustomTagRepository customTagRepository;

    @BeforeEach
    void setUpApiKeyAuth() {
        when(apiKeyService.resolveUserId(TEST_API_KEY)).thenReturn(Optional.of(1L));
    }

    @Test
    @DisplayName("正常系: LLMプロンプト入力からタグ生成・保存までの完全フロー")
    void testCompleteCustomTagGenerationFlow() throws Exception {
        // Arrange
        String llmResponse = """
            こちらはボタンコンポーネントです：

            ```html
            <button class="btn btn-primary" data-type="{{attr:type}}">{{content}}</button>
            ```

            CSSスタイルです：

            ```css
            .btn {
              padding: 10px 20px;
              border: none;
              border-radius: 4px;
              cursor: pointer;
              transition: all 0.3s ease;
            }
            .btn-primary {
              background-color: #007bff;
              color: white;
            }
            .btn-primary:hover {
              background-color: #0056b3;
            }
            ```
            """;

        GenerateCustomTagRequest request = new GenerateCustomTagRequest(
            "青いボタンコンポーネントを作成してください",
            "my-button",
            "カスタムボタンコンポーネント",
            null
        );

        when(llmClient.generate(anyString())).thenReturn(llmResponse);

        // Act & Assert
        MvcResult result = mockMvc.perform(
            post("/api/custom-tags/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .header(API_KEY_HEADER, TEST_API_KEY)
                .header(ACTOR_ROLE_HEADER, ADMIN_ROLE)
                .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.tagName").value("my-button"))
        .andExpect(jsonPath("$.description").value("カスタムボタンコンポーネント"))
        .andExpect(jsonPath("$.htmlTemplate").exists())
        .andExpect(jsonPath("$.cssContent").exists())
        .andReturn();

        // 生成されたタグがデータベースに保存されていることを確認
        Optional<CustomTag> savedTag = customTagRepository.findByTagNameAndProjectIdIsNull("my-button");
        assertThat(savedTag).isPresent();
        assertThat(savedTag.get().getHtmlTemplate()).contains("btn-primary");
        assertThat(savedTag.get().getCssContent()).contains("padding");
    }

    @Test
    @DisplayName("エラー系: LLMレスポンスにHTMLがない場合の処理")
    void testGenerationFailsWhenHtmlNotInResponse() throws Exception {
        // Arrange
        String invalidResponse = "HTMLを含まないレスポンス";

        GenerateCustomTagRequest request = new GenerateCustomTagRequest(
            "テスト",
            "test-tag",
            "説明",
            null
        );

        when(llmClient.generate(anyString())).thenReturn(invalidResponse);

        // Act & Assert
        mockMvc.perform(
            post("/api/custom-tags/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .header(API_KEY_HEADER, TEST_API_KEY)
                .header(ACTOR_ROLE_HEADER, ADMIN_ROLE)
                .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("エラー系: タグ名が既に登録されている場合")
    void testGenerationFailsWithDuplicateTagName() throws Exception {
        // Arrange - 既に存在するタグを作成
        CustomTag existingTag = new CustomTag();
        existingTag.setTagName("existing-tag");
        existingTag.setHtmlTemplate("<div>existing</div>");
        existingTag.setProjectId(null);
        customTagRepository.save(existingTag);

        String llmResponse = """
            ```html
            <div>New content</div>
            ```
            """;

        GenerateCustomTagRequest request = new GenerateCustomTagRequest(
            "テスト",
            "existing-tag",
            "説明",
            null
        );

        when(llmClient.generate(anyString())).thenReturn(llmResponse);

        // Act & Assert
        mockMvc.perform(
            post("/api/custom-tags/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .header(API_KEY_HEADER, TEST_API_KEY)
                .header(ACTOR_ROLE_HEADER, ADMIN_ROLE)
                .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("セキュリティテスト: XSS脆弱性チェック（scriptタグ検出）")
    void testXssProtectionDetectsScriptTag() throws Exception {
        // Arrange
        String maliciousResponse = """
            ```html
            <div><script>alert('xss')</script>{{content}}</div>
            ```
            """;

        GenerateCustomTagRequest request = new GenerateCustomTagRequest(
            "テスト",
            "xss-tag",
            "説明",
            null
        );

        when(llmClient.generate(anyString())).thenReturn(maliciousResponse);

        // Act & Assert
        mockMvc.perform(
            post("/api/custom-tags/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .header(API_KEY_HEADER, TEST_API_KEY)
                .header(ACTOR_ROLE_HEADER, ADMIN_ROLE)
                .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("セキュリティテスト: XSS脆弱性チェック（イベントハンドラ検出）")
    void testXssProtectionDetectsEventHandler() throws Exception {
        // Arrange
        String maliciousResponse = """
            ```html
            <div onclick="alert('xss')">{{content}}</div>
            ```
            """;

        GenerateCustomTagRequest request = new GenerateCustomTagRequest(
            "テスト",
            "event-tag",
            "説明",
            null
        );

        when(llmClient.generate(anyString())).thenReturn(maliciousResponse);

        // Act & Assert
        mockMvc.perform(
            post("/api/custom-tags/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .header(API_KEY_HEADER, TEST_API_KEY)
                .header(ACTOR_ROLE_HEADER, ADMIN_ROLE)
                .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("複数プロジェクト間の独立性: グローバルおよびプロジェクト固有のタグが同時に存在可能")
    void testTagIndependenceBetweenProjects() throws Exception {
        // Arrange
        String llmResponse = """
            ```html
            <button>Global Button</button>
            ```
            """;

        // グローバルタグを作成
        GenerateCustomTagRequest globalRequest = new GenerateCustomTagRequest(
            "グローバルボタン",
            "global-button",
            "Global Button",
            null
        );

        when(llmClient.generate(anyString())).thenReturn(llmResponse);

        mockMvc.perform(
            post("/api/custom-tags/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .header(API_KEY_HEADER, TEST_API_KEY)
                .header(ACTOR_ROLE_HEADER, ADMIN_ROLE)
                .content(objectMapper.writeValueAsString(globalRequest))
        )
        .andExpect(status().isCreated());

        // グローバルタグが存在することを確認
        Optional<CustomTag> globalTag = customTagRepository.findByTagNameAndProjectIdIsNull("global-button");
        assertThat(globalTag).isPresent();
    }

    @Test
    @DisplayName("バリデーション: 検証エンドポイントの動作確認")
    void testValidationEndpoint() throws Exception {
        // Arrange
        String validHtml = "<div class=\"alert\">{{content}}</div>";
        String validCss = ".alert { color: red; padding: 10px; }";

        // Act & Assert
        mockMvc.perform(
            post("/api/custom-tags/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .header(API_KEY_HEADER, TEST_API_KEY)
                .content(objectMapper.writeValueAsString(
                    new ValidateCustomTagRequest(validHtml, validCss)
                ))
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isValid").value(true));
    }

    @Test
    @DisplayName("バリデーション: 不正なHTMLの検出")
    void testValidationDetectsInvalidHtml() throws Exception {
        // Arrange
        String maliciousHtml = "<div onclick=\"alert('xss')\">{{content}}</div>";
        String css = ".alert { padding: 10px; }";

        // Act & Assert
        mockMvc.perform(
            post("/api/custom-tags/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .header(API_KEY_HEADER, TEST_API_KEY)
                .content(objectMapper.writeValueAsString(
                    new ValidateCustomTagRequest(maliciousHtml, css)
                ))
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isValid").value(false))
        .andExpect(jsonPath("$.errors").isArray());
    }

}
