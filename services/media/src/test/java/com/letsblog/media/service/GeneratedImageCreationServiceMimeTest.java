package com.letsblog.media.service;

import com.letsblog.media.ai.GeneratedImageStorageService;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import com.letsblog.media.messaging.DomainEventPublisher;
import com.letsblog.media.repository.GeneratedImageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** issue #1599: image/jpegの画像は.jpgで保存し、promptが無い行も登録できる。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GeneratedImageCreationService のMIME別保存(issue #1599)")
class GeneratedImageCreationServiceMimeTest {

    @Mock
    private GeneratedImageRepository repository;
    @Mock
    private GeneratedImageStorageService storage;
    @Mock
    private DomainEventPublisher publisher;

    private CreateGeneratedImageRequest request(String mimeType) {
        return new CreateGeneratedImageRequest(7L, null, null, null, null, null, null, null,
                1920, 1080, null, null, null, null, null, mimeType, "UPLOAD", null, new byte[] {1});
    }

    @Test
    @DisplayName("image/jpegは拡張子jpgで保存され、promptはnullのまま行が作られる")
    void jpegはjpgで保存() {
        when(storage.store(7L, new byte[] {1}, "jpg")).thenReturn("7/0001.jpg");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        GeneratedImageCreationService service = new GeneratedImageCreationService(repository, storage, publisher);

        service.create(request("image/jpeg"));

        ArgumentCaptor<GeneratedImage> captor = ArgumentCaptor.forClass(GeneratedImage.class);
        verify(repository).save(captor.capture());
        assertEquals("7/0001.jpg", captor.getValue().getFilePath());
        assertEquals("image/jpeg", captor.getValue().getMimeType());
        assertEquals("UPLOAD", captor.getValue().getProvider());
        assertNull(captor.getValue().getPrompt());
    }

    @Test
    @DisplayName("image/pngは従来どおりの保存経路(.png)を通る")
    void pngは従来どおり() {
        when(storage.store(7L, new byte[] {1})).thenReturn("7/0001.png");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        GeneratedImageCreationService service = new GeneratedImageCreationService(repository, storage, publisher);

        service.create(request("image/png"));

        verify(storage).store(7L, new byte[] {1});
    }
}
