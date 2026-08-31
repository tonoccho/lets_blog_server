package com.letsblog.media.service;

import com.letsblog.media.ai.GeneratedImageStorageService;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import com.letsblog.media.messaging.DomainEventPublisher;
import com.letsblog.media.repository.GeneratedImageRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生成画像の保存(ファイル保存 + {@code generated_images} 行作成 + イベント発行)の検証。
 *
 * <p>issue #573 stage4 で {@code POST /api/generated-images} として追加された処理で、
 * 当時は {@code GeneratedImageController} に直接書かれていたため
 * {@code GeneratedImageControllerTest} が検証していた。issue #583 で画像生成本体が
 * media-service へ移り、{@link ImageGenerationService} からも同じ処理を呼ぶ必要が生じたので
 * サービスへ切り出した。検証もこちらへ移している(コントローラ側は委譲と認可の確認に絞った)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("media-service: 生成画像の保存(issue #573 stage4 / #583で切り出し)")
class GeneratedImageCreationServiceTest {

    @Mock
    private GeneratedImageRepository generatedImageRepository;

    @Mock
    private GeneratedImageStorageService generatedImageStorageService;

    @Mock
    private DomainEventPublisher domainEventPublisher;

    private GeneratedImageCreationService service;

    @BeforeEach
    void setUp() {
        service = new GeneratedImageCreationService(
                generatedImageRepository, generatedImageStorageService, domainEventPublisher);
    }

    @Test
    void ファイルを保存しパラメータ込みで画像行を作成する() {
        byte[] imageData = new byte[]{1, 2, 3};
        when(generatedImageStorageService.store(1L, imageData)).thenReturn("1/0001.png");
        when(generatedImageRepository.save(any(GeneratedImage.class))).thenAnswer(inv -> {
            GeneratedImage image = inv.getArgument(0);
            image.setId(42L);
            image.setCreatedAt(LocalDateTime.now());
            return image;
        });

        CreateGeneratedImageRequest request = new CreateGeneratedImageRequest(
                1L, "a cat", "blurry", 20, 7.0, "euler", "normal", 123L, 512, 512, 1,
                "checkpoint.safetensors", null, null, "image/png", "COMFYUI", "[\"猫\"]", imageData);

        GeneratedImage saved = service.create(request);

        assertEquals(42L, saved.getId());
        assertEquals("a cat", saved.getPrompt());
        assertEquals("COMFYUI", saved.getProvider());
        assertEquals("[\"猫\"]", saved.getTagsJson());

        ArgumentCaptor<GeneratedImage> savedCaptor = ArgumentCaptor.forClass(GeneratedImage.class);
        verify(generatedImageRepository).save(savedCaptor.capture());
        GeneratedImage captured = savedCaptor.getValue();
        assertEquals("1/0001.png", captured.getFilePath());
        assertEquals(1L, captured.getProjectId());
        assertEquals("blurry", captured.getNegativePrompt());
        assertEquals(20, captured.getSteps());
        assertEquals(0, BigDecimal.valueOf(7.0).compareTo(captured.getCfgScale()));
        assertEquals("euler", captured.getSamplerName());
        assertEquals("normal", captured.getScheduler());
        assertEquals(123L, captured.getSeed());
        assertEquals(512, captured.getWidth());
        assertEquals(512, captured.getHeight());
        assertEquals("checkpoint.safetensors", captured.getCheckpoint());
        assertEquals("image/png", captured.getMimeType());

        verify(domainEventPublisher).publishImageGenerated(42L, 1L);
    }

    /** {@code loraWeight}/{@code cfgScale} は null 可。BigDecimal 変換で NPE にならないこと。 */
    @Test
    void 数値の任意項目がnullでも保存できる() {
        byte[] imageData = new byte[]{9};
        when(generatedImageStorageService.store(null, imageData)).thenReturn("shared/0001.png");
        when(generatedImageRepository.save(any(GeneratedImage.class))).thenAnswer(inv -> {
            GeneratedImage image = inv.getArgument(0);
            image.setId(7L);
            return image;
        });

        CreateGeneratedImageRequest request = new CreateGeneratedImageRequest(
                null, "a dog", null, null, null, null, null, null, null, null, null,
                null, null, null, "image/png", "CHATGPT", null, imageData);

        GeneratedImage saved = service.create(request);

        assertEquals(7L, saved.getId());
        verify(domainEventPublisher).publishImageGenerated(7L, null);
    }
}
