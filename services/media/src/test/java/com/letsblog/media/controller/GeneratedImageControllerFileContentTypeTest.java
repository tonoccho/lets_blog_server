package com.letsblog.media.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.GeneratedImageStorageService;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.messaging.DomainEventPublisher;
import com.letsblog.media.repository.GeneratedImageRepository;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.GeneratedImageCreationService;
import com.letsblog.media.service.GeneratedImageFolderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** issue #1599: ファイル取得は、保存しているMIME(JPEG/PNG)で返す。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GeneratedImageController#getImageFile のContent-Type(issue #1599)")
class GeneratedImageControllerFileContentTypeTest {

    @Mock
    private GeneratedImageRepository repository;
    @Mock
    private GeneratedImageStorageService storage;
    @Mock
    private DomainEventPublisher publisher;
    @Mock
    private AdminAuthorizationService authorization;
    @Mock
    private GeneratedImageCreationService creation;
    @Mock
    private GeneratedImageFolderService folders;

    private GeneratedImageController controller;

    @BeforeEach
    void setUp() {
        controller = new GeneratedImageController(
                repository, storage, new ObjectMapper(), publisher, authorization, creation, folders);
    }

    private void stub(String mimeType, String path) {
        GeneratedImage image = new GeneratedImage();
        image.setId(3L);
        image.setProjectId(7L);
        image.setFilePath(path);
        image.setMimeType(mimeType);
        when(repository.findById(3L)).thenReturn(Optional.of(image));
        when(storage.load(path)).thenReturn(new byte[] {1});
    }

    @Test
    @DisplayName("image/jpegの画像はimage/jpegと.jpgのファイル名で返る")
    void jpegはjpegで返る() {
        stub("image/jpeg", "7/0001.jpg");

        ResponseEntity<byte[]> response = controller.getImageFile(3L);

        assertEquals("image/jpeg", response.getHeaders().getContentType().toString());
        assertEquals("inline; filename=3.jpg", response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    }

    @Test
    @DisplayName("image/pngの画像は従来どおりimage/pngと.pngのファイル名で返る")
    void pngはpngで返る() {
        stub("image/png", "7/0001.png");

        ResponseEntity<byte[]> response = controller.getImageFile(3L);

        assertEquals("image/png", response.getHeaders().getContentType().toString());
        assertEquals("inline; filename=3.png", response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    }
}
