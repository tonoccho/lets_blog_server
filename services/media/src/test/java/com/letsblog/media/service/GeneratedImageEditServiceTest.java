package com.letsblog.media.service;

import com.letsblog.media.ai.GeneratedImageStorageService;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import com.letsblog.media.repository.GeneratedImageRepository;
import com.letsblog.media.testsupport.UploadImageFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** issue #1655: 編集結果を、元の画像のタグ・フォルダ・種別を引き継ぐ新しい画像として登録する。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GeneratedImageEditService(issue #1655)")
class GeneratedImageEditServiceTest {

    @Mock
    private GeneratedImageStorageService storage;
    @Mock
    private GeneratedImageCreationService creationService;
    @Mock
    private GeneratedImageRepository repository;

    private GeneratedImageEditService service;

    @BeforeEach
    void setUp() {
        service = new GeneratedImageEditService(storage, new ImageResizeService(), creationService, repository);
    }

    private static GeneratedImage source(String provider, String mime, Long folderId, String tags) {
        GeneratedImage image = new GeneratedImage();
        image.setId(11L);
        image.setProjectId(7L);
        image.setFilePath("7/a.png");
        image.setMimeType(mime);
        image.setProvider(provider);
        image.setFolderId(folderId);
        image.setTagsJson(tags);
        image.setSourceImageId(3L);
        image.setPrompt("a cat");
        return image;
    }

    @Test
    @DisplayName("タグ・フォルダ・providerを引き継ぎ、sourceImageIdは持たない新しい画像を登録する")
    void 引き継いで登録する() throws IOException {
        GeneratedImage src = source("COMFYUI", "image/png", 5L, "[\"cat\"]");
        when(storage.load("7/a.png")).thenReturn(UploadImageFixtures.png(UploadImageFixtures.solid(40, 20, Color.RED, false)));
        GeneratedImage created = new GeneratedImage();
        created.setId(12L);
        when(creationService.create(any())).thenReturn(created);
        when(repository.save(created)).thenReturn(created);

        GeneratedImage result = service.edit(src, List.of(ImageEditOperation.ROTATE_CW), null);

        assertEquals(created, result);
        ArgumentCaptor<CreateGeneratedImageRequest> captor = ArgumentCaptor.forClass(CreateGeneratedImageRequest.class);
        verify(creationService).create(captor.capture());
        CreateGeneratedImageRequest request = captor.getValue();
        assertEquals(7L, request.projectId());
        assertEquals("COMFYUI", request.provider());
        assertEquals("[\"cat\"]", request.tagsJson());
        assertEquals("image/png", request.mimeType());
        assertEquals(20, request.width());
        assertEquals(40, request.height());
        assertNull(request.sourceImageId());
        // promptは引き継ぐ(AI生成の画像が、一覧で「アップロード画像」と表示されないようにする)
        assertEquals("a cat", request.prompt());
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(request.imageData()));
        assertEquals(20, stored.getWidth());
        assertEquals(5L, created.getFolderId());
    }

    @Test
    @DisplayName("元が未分類なら新しい画像も未分類のまま、保存し直さない")
    void 未分類は未分類のまま() {
        GeneratedImage src = source("UPLOAD", "image/jpeg", null, null);
        when(storage.load("7/a.png")).thenReturn(UploadImageFixtures.jpeg(UploadImageFixtures.solid(40, 20, Color.RED, false)));
        GeneratedImage created = new GeneratedImage();
        when(creationService.create(any())).thenReturn(created);

        service.edit(src, List.of(), new ImageCropRegion(0, 0, 10, 10));

        ArgumentCaptor<CreateGeneratedImageRequest> captor = ArgumentCaptor.forClass(CreateGeneratedImageRequest.class);
        verify(creationService).create(captor.capture());
        assertEquals("UPLOAD", captor.getValue().provider());
        assertEquals("image/jpeg", captor.getValue().mimeType());
        assertNull(captor.getValue().tagsJson());
        assertNull(created.getFolderId());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("編集に失敗したら何も登録しない")
    void 失敗したら登録しない() {
        GeneratedImage src = source("UPLOAD", "image/png", 5L, null);
        when(storage.load("7/a.png")).thenReturn(UploadImageFixtures.png(UploadImageFixtures.solid(40, 20, Color.RED, false)));

        assertThrows(InvalidImageUploadException.class,
                () -> service.edit(src, List.of(), new ImageCropRegion(30, 0, 20, 10)));

        verifyNoInteractions(creationService);
        verifyNoInteractions(repository);
    }
}
