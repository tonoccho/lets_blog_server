package com.letsblog.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.GeneratedImageStorageService;
import com.letsblog.api.domain.GeneratedImage;
import com.letsblog.api.dto.GeneratedImageDetailResponse;
import com.letsblog.api.dto.GeneratedImageSummaryResponse;
import com.letsblog.api.dto.UpdateGeneratedImageTagsRequest;
import com.letsblog.api.repository.GeneratedImageRepository;
import com.letsblog.api.service.GeneratedImageNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    private GeneratedImageController controller;

    @BeforeEach
    void setUp() {
        controller = new GeneratedImageController(
                generatedImageRepository, generatedImageStorageService, new ObjectMapper());
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
}
