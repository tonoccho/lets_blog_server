package com.letsblog.media.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.GeneratedImageStorageService;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import com.letsblog.media.dto.GeneratedImageDetailResponse;
import com.letsblog.media.dto.GeneratedImageSummaryResponse;
import com.letsblog.media.dto.UpdateGeneratedImageTagsRequest;
import com.letsblog.media.messaging.DomainEventPublisher;
import com.letsblog.media.repository.GeneratedImageRepository;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.GeneratedImageCreationService;
import com.letsblog.media.service.GeneratedImageNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GeneratedImageControllerの回帰テスト(issue #281)。タグのJSONパース/シリアライズ、
 * tagクエリパラメータによる絞り込み、タグ更新エンドポイントを検証する。
 */
@ExtendWith(MockitoExtension.class)
class GeneratedImageControllerTest {

    @Mock
    private GeneratedImageRepository generatedImageRepository;
    @Mock
    private GeneratedImageStorageService generatedImageStorageService;
    @Mock
    private DomainEventPublisher domainEventPublisher;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private GeneratedImageCreationService generatedImageCreationService;

    private GeneratedImageController controller;

    @BeforeEach
    void setUp() {
        controller = new GeneratedImageController(
                generatedImageRepository, generatedImageStorageService, new ObjectMapper(), domainEventPublisher,
                adminAuthorizationService, generatedImageCreationService);
    }

    private GeneratedImage buildImage(Long id, String prompt, String tagsJson) {
        GeneratedImage image = new GeneratedImage();
        image.setId(id);
        image.setPrompt(prompt);
        image.setFilePath("path/" + id + ".png");
        image.setMimeType("image/png");
        image.setTagsJson(tagsJson);
        image.setCreatedAt(LocalDateTime.now());
        return image;
    }

    @Test
    void list_tag未指定時は全件を返す() {
        when(generatedImageRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                buildImage(1L, "a cat", "[\"猫\",\"動物\"]"),
                buildImage(2L, "a dog", "[\"犬\"]")));

        List<GeneratedImageSummaryResponse> result = controller.list(null, null);

        assertEquals(2, result.size());
    }

    @Test
    void list_tag指定時は大文字小文字を区別せず一致する画像だけ返す() {
        when(generatedImageRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                buildImage(1L, "a cat", "[\"猫\",\"動物\"]"),
                buildImage(2L, "a dog", "[\"犬\"]")));

        List<GeneratedImageSummaryResponse> result = controller.list(null, "猫");

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
        assertEquals(List.of("猫", "動物"), result.get(0).tags());
    }

    @Test
    void list_タグ未設定の画像は空リストとして扱う() {
        when(generatedImageRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(buildImage(1L, "a cat", null)));

        List<GeneratedImageSummaryResponse> result = controller.list(null, null);

        assertEquals(List.of(), result.get(0).tags());
    }

    @Test
    void list_不正なJSONは空リストとして扱う() {
        when(generatedImageRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(buildImage(1L, "a cat", "not json")));

        List<GeneratedImageSummaryResponse> result = controller.list(null, null);

        assertEquals(List.of(), result.get(0).tags());
    }

    @Test
    void get_タグ込みの詳細を返す() {
        when(generatedImageRepository.findById(1L))
                .thenReturn(Optional.of(buildImage(1L, "a cat", "[\"猫\"]")));

        GeneratedImageDetailResponse result = controller.get(1L);

        assertEquals(List.of("猫"), result.tags());
    }

    @Test
    void get_存在しないIDはGeneratedImageNotFoundExceptionを投げる() {
        when(generatedImageRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(GeneratedImageNotFoundException.class, () -> controller.get(99L));
    }

    @Test
    void updateTags_タグを更新して保存する() {
        GeneratedImage image = buildImage(1L, "a cat", "[\"猫\"]");
        when(generatedImageRepository.findById(1L)).thenReturn(Optional.of(image));
        when(generatedImageRepository.save(any(GeneratedImage.class))).thenAnswer(inv -> inv.getArgument(0));

        GeneratedImageDetailResponse result = controller.updateTags(
                1L, new UpdateGeneratedImageTagsRequest(List.of("猫", "かわいい")));

        assertEquals(List.of("猫", "かわいい"), result.tags());
        assertTrue(image.getTagsJson().contains("かわいい"));
    }

    @Test
    void updateTags_空リストを渡すとタグが解除される() {
        GeneratedImage image = buildImage(1L, "a cat", "[\"猫\"]");
        when(generatedImageRepository.findById(1L)).thenReturn(Optional.of(image));
        when(generatedImageRepository.save(any(GeneratedImage.class))).thenAnswer(inv -> inv.getArgument(0));

        GeneratedImageDetailResponse result = controller.updateTags(
                1L, new UpdateGeneratedImageTagsRequest(List.of()));

        assertEquals(List.of(), result.tags());
    }

    /**
     * {@code POST /api/generated-images} は、認可を確認したうえで保存処理を
     * {@link GeneratedImageCreationService} へ委譲する(issue #583で切り出した)。
     * 保存処理そのものの検証は {@code GeneratedImageCreationServiceTest} が行う。
     */
    @Test
    void create_認可を確認してから保存サービスへ委譲する() {
        byte[] imageData = new byte[]{1, 2, 3};
        CreateGeneratedImageRequest request = new CreateGeneratedImageRequest(
                1L, "a cat", "blurry", 20, 7.0, "euler", "normal", 123L, 512, 512, 1,
                "checkpoint.safetensors", null, null, "image/png", "COMFYUI", "[\"猫\"]", imageData);

        GeneratedImage saved = buildImage(42L, "a cat", "[\"猫\"]");
        saved.setProvider("COMFYUI");
        when(generatedImageCreationService.create(request)).thenReturn(saved);

        GeneratedImageDetailResponse response = controller.create(request);

        assertEquals(42L, response.id());
        assertEquals("a cat", response.prompt());
        assertEquals("COMFYUI", response.provider());
        assertEquals(List.of("猫"), response.tags());

        // ファイル保存が始まる前にプロジェクトメンバー判定を通していること(issue #830)。
        verify(adminAuthorizationService).requireProjectMemberOrAdminForResource(1L);
        verify(generatedImageCreationService).create(request);
    }
}
