package com.letsblog.media.controller;

import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** issue #1599: ファイル取得は、保存しているMIME(JPEG/PNG)で返す。 */
@DisplayName("GeneratedImageController#getImageFile のContent-Type(issue #1599)")
class GeneratedImageControllerFileContentTypeTest {

    /** 保存サービス・認可は実物。DB(リポジトリ)・MQ・identity-serviceだけを差し替える(issue #1820)。 */
    private final MediaWorld world = new MediaWorld();

    private GeneratedImageController controller;

    @BeforeEach
    void setUp() {
        world.actingAsAdmin(1L);
        controller = new GeneratedImageController(
                world.generatedImageRepository, world.storageService, world.objectMapper,
                world.domainEventPublisher, world.adminAuthorizationService, world.creationService,
                world.folderService);
    }

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    @DisplayName("image/jpegの画像はimage/jpegと.jpgのファイル名で返る")
    void jpegはjpegで返る() {
        world.existingImage(3L, 7L, new byte[] {1}, "image/jpeg");

        ResponseEntity<byte[]> response = controller.getImageFile(3L);

        assertEquals("image/jpeg", response.getHeaders().getContentType().toString());
        assertEquals("inline; filename=3.jpg", response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertArrayEquals(new byte[] {1}, response.getBody());
    }

    @Test
    @DisplayName("image/pngの画像は従来どおりimage/pngと.pngのファイル名で返る")
    void pngはpngで返る() {
        world.existingImage(3L, 7L, new byte[] {1}, "image/png");

        ResponseEntity<byte[]> response = controller.getImageFile(3L);

        assertEquals("image/png", response.getHeaders().getContentType().toString());
        assertEquals("inline; filename=3.png", response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertArrayEquals(new byte[] {1}, response.getBody());
    }
}
