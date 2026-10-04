package com.letsblog.media.service;

import com.letsblog.media.ai.GeneratedImageStorageService;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import com.letsblog.media.messaging.DomainEventPublisher;
import com.letsblog.media.repository.GeneratedImageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 生成画像の行に参照元の画像ID(issue #1601)が残ること。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("media-service: 生成画像の参照元ID(issue #1601)")
class GeneratedImageCreationServiceSourceTest {

    @Mock
    private GeneratedImageRepository repository;
    @Mock
    private GeneratedImageStorageService storage;
    @Mock
    private DomainEventPublisher publisher;

    private GeneratedImageCreationService service;

    @BeforeEach
    void setUp() {
        service = new GeneratedImageCreationService(repository, storage, publisher);
        lenient().when(storage.store(any(), any())).thenReturn("1/0001.png");
        lenient().when(repository.save(any(GeneratedImage.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static CreateGeneratedImageRequest request(Long sourceImageId) {
        return new CreateGeneratedImageRequest(
                1L, "a cat", null, 20, 7.0, "euler", "normal", 1L, 512, 512, 1, 0, "c", null, null,
                "image/png", "COMFYUI", null, new byte[] {1}, sourceImageId);
    }

    @Test
    void 参照元の画像IDを行に保存する() {
        service.create(request(5L));

        ArgumentCaptor<GeneratedImage> captor = ArgumentCaptor.forClass(GeneratedImage.class);
        verify(repository).save(captor.capture());
        assertEquals(5L, captor.getValue().getSourceImageId());
    }

    @Test
    void 参照元が無ければnullのまま() {
        service.create(request(null));

        ArgumentCaptor<GeneratedImage> captor = ArgumentCaptor.forClass(GeneratedImage.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getSourceImageId());
    }

    @Test
    void 参照元を持たない従来の19引数コンストラクタでも保存できる() {
        CreateGeneratedImageRequest legacy = new CreateGeneratedImageRequest(
                1L, "a cat", null, 20, 7.0, "euler", "normal", 1L, 512, 512, 1, 0, "c", null, null,
                "image/png", "COMFYUI", null, new byte[] {1});

        assertNull(legacy.sourceImageId());
    }
}
