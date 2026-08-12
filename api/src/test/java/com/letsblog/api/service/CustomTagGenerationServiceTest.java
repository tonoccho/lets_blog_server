package com.letsblog.api.service;

import com.letsblog.api.ai.OllamaClient;
import com.letsblog.api.ai.PenpotClient;
import com.letsblog.api.domain.CustomTag;
import com.letsblog.api.dto.GenerateCustomTagRequest;
import com.letsblog.api.dto.GenerateCustomTagResponse;
import com.letsblog.api.repository.CustomTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomTagGenerationServiceTest {

    @Mock
    private OllamaClient ollamaClient;

    @Mock
    private PenpotClient penpotClient;

    @Mock
    private CustomTagRepository customTagRepository;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    @Spy
    private CustomTagValidationService customTagValidationService = new CustomTagValidationService();

    @InjectMocks
    private CustomTagGenerationService customTagGenerationService;

    @BeforeEach
    void setup() {
        doNothing().when(adminAuthorizationService).requireAdmin();
    }

    @Test
    void testGenerateCustomTag_Success() {
        // Arrange
        String ollamaResponse = """
            こちらはボタンコンポーネントです：

            ```html
            <button class="btn btn-primary">クリック</button>
            ```

            CSSスタイルです：

            ```css
            .btn {
              padding: 10px 20px;
              border: none;
              border-radius: 4px;
              cursor: pointer;
            }
            .btn-primary {
              background-color: #007bff;
              color: white;
            }
            ```
            """;

        GenerateCustomTagRequest request = new GenerateCustomTagRequest(
            "青いボタンコンポーネントを作成してください",
            "my-button",
            "カスタムボタンコンポーネント",
            null
        );

        CustomTag savedTag = new CustomTag();
        savedTag.setId(1L);
        savedTag.setTagName("my-button");
        savedTag.setHtmlTemplate("<button class=\"btn btn-primary\">クリック</button>");
        savedTag.setCssContent(".btn {\n  padding: 10px 20px;\n  border: none;\n  border-radius: 4px;\n  cursor: pointer;\n}\n.btn-primary {\n  background-color: #007bff;\n  color: white;\n}");
        savedTag.setDescription("カスタムボタンコンポーネント");
        savedTag.setProjectId(null);

        when(ollamaClient.generate(anyString())).thenReturn(ollamaResponse);
        when(customTagRepository.findByTagNameAndProjectIdIsNull("my-button")).thenReturn(Optional.empty());
        when(customTagRepository.save(any(CustomTag.class))).thenReturn(savedTag);

        // Act
        GenerateCustomTagResponse response = customTagGenerationService.generate(request);

        // Assert
        assertNotNull(response);
        assertEquals(1L, response.id());
        assertEquals("my-button", response.tagName());
        assertTrue(response.htmlTemplate().contains("btn"));
        assertTrue(response.cssContent().contains("padding"));
        assertEquals("カスタムボタンコンポーネント", response.description());

        verify(ollamaClient).generate(anyString());
        verify(customTagRepository).save(any(CustomTag.class));
    }

    @Test
    void testGenerateCustomTag_NoHtmlInResponse() {
        // Arrange
        String ollamaResponse = "HTMLなしのレスポンス";

        GenerateCustomTagRequest request = new GenerateCustomTagRequest(
            "テスト",
            "test-tag",
            "説明",
            null
        );

        when(ollamaClient.generate(anyString())).thenReturn(ollamaResponse);

        // Act & Assert
        InvalidCustomTagContentException exception = assertThrows(
            InvalidCustomTagContentException.class,
            () -> customTagGenerationService.generate(request)
        );

        assertTrue(exception.getMessage().contains("HTMLを抽出できません"));
        verify(customTagRepository, never()).save(any(CustomTag.class));
    }

    @Test
    void testGenerateCustomTag_DuplicateTagName() {
        // Arrange
        String ollamaResponse = """
            ```html
            <div>Test</div>
            ```
            """;

        GenerateCustomTagRequest request = new GenerateCustomTagRequest(
            "テスト",
            "existing-tag",
            "説明",
            null
        );

        CustomTag existingTag = new CustomTag();
        existingTag.setId(1L);
        existingTag.setTagName("existing-tag");

        when(ollamaClient.generate(anyString())).thenReturn(ollamaResponse);
        when(customTagRepository.findByTagNameAndProjectIdIsNull("existing-tag"))
            .thenReturn(Optional.of(existingTag));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> customTagGenerationService.generate(request)
        );

        assertTrue(exception.getMessage().contains("既に登録されています"));
        verify(customTagRepository, never()).save(any(CustomTag.class));
    }

    @Test
    void testExtractHtml() {
        String response = """
            Some text before
            ```html
            <div class="card">
              <h2>Title</h2>
            </div>
            ```
            Some text after
            """;

        GenerateCustomTagRequest request = new GenerateCustomTagRequest("test", "tag", null, null);
        when(ollamaClient.generate(anyString())).thenReturn(response);
        when(customTagRepository.findByTagNameAndProjectIdIsNull("tag")).thenReturn(Optional.empty());

        CustomTag savedTag = new CustomTag();
        savedTag.setId(1L);
        savedTag.setTagName("tag");
        savedTag.setHtmlTemplate("<div class=\"card\">\n  <h2>Title</h2>\n</div>");
        when(customTagRepository.save(any(CustomTag.class))).thenReturn(savedTag);

        GenerateCustomTagResponse result = customTagGenerationService.generate(request);
        assertTrue(result.htmlTemplate().contains("card"));
    }

    @Test
    void testGenerateCustomTag_PenpotDesignFileCreated() {
        String ollamaResponse = """
            ```html
            <button class="btn">クリック</button>
            ```

            ```css
            .btn { padding: 10px; }
            ```
            """;

        GenerateCustomTagRequest request = new GenerateCustomTagRequest(
            "青いボタンコンポーネントを作成してください", "my-button", "説明", null
        );

        PenpotClient.DesignFile designFile = new PenpotClient.DesignFile(
            "file-id", "project-id", "http://localhost:9001/#/workspace/project-id/file-id?page-id=page-id"
        );

        when(ollamaClient.generate(anyString())).thenReturn(ollamaResponse);
        when(customTagRepository.findByTagNameAndProjectIdIsNull("my-button")).thenReturn(Optional.empty());
        when(penpotClient.createDesignFile(anyString(), anyString())).thenReturn(designFile);
        when(customTagRepository.save(any(CustomTag.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GenerateCustomTagResponse response = customTagGenerationService.generate(request);

        assertEquals(designFile.url(), response.penpotFileUrl());
        verify(penpotClient).createDesignFile(eq("カスタムタグ: my-button"), anyString());
    }

    @Test
    void testGenerateCustomTag_PenpotFailureDoesNotBlockGeneration() {
        String ollamaResponse = """
            ```html
            <button class="btn">クリック</button>
            ```
            """;

        GenerateCustomTagRequest request = new GenerateCustomTagRequest("テスト", "my-button", "説明", null);

        when(ollamaClient.generate(anyString())).thenReturn(ollamaResponse);
        when(customTagRepository.findByTagNameAndProjectIdIsNull("my-button")).thenReturn(Optional.empty());
        when(penpotClient.createDesignFile(anyString(), anyString()))
            .thenThrow(new com.letsblog.api.ai.AiServiceException("接続失敗", null));
        when(customTagRepository.save(any(CustomTag.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GenerateCustomTagResponse response = customTagGenerationService.generate(request);

        assertNull(response.penpotFileUrl());
        verify(customTagRepository).save(any(CustomTag.class));
    }

    @Test
    void testExtractCss() {
        String response = """
            ```html
            <div>test</div>
            ```

            Some text

            ```css
            .card {
              background: white;
              border-radius: 8px;
            }
            ```
            """;

        GenerateCustomTagRequest request = new GenerateCustomTagRequest("test", "tag", null, null);
        when(ollamaClient.generate(anyString())).thenReturn(response);
        when(customTagRepository.findByTagNameAndProjectIdIsNull("tag")).thenReturn(Optional.empty());

        CustomTag savedTag = new CustomTag();
        savedTag.setId(1L);
        savedTag.setTagName("tag");
        savedTag.setHtmlTemplate("<div>test</div>");
        savedTag.setCssContent(".card {\n  background: white;\n  border-radius: 8px;\n}");
        when(customTagRepository.save(any(CustomTag.class))).thenReturn(savedTag);

        GenerateCustomTagResponse result = customTagGenerationService.generate(request);
        assertTrue(result.cssContent().contains("background"));
    }
}
