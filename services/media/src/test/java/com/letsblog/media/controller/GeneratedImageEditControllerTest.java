package com.letsblog.media.controller;

import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.EditGeneratedImageRequest;
import com.letsblog.media.dto.GeneratedImageDetailResponse;
import com.letsblog.media.repository.GeneratedImageRepository;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.ForbiddenException;
import com.letsblog.media.service.GeneratedImageEditService;
import com.letsblog.media.service.GeneratedImageNotFoundException;
import com.letsblog.media.service.ImageCropRegion;
import com.letsblog.media.service.ImageEditOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** issue #1655: POST /api/generated-images/{id}/edit。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GeneratedImageEditController(issue #1655)")
class GeneratedImageEditControllerTest {

    @Mock
    private GeneratedImageRepository repository;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private GeneratedImageEditService editService;

    private GeneratedImageEditController controller;

    @BeforeEach
    void setUp() {
        controller = new GeneratedImageEditController(repository, adminAuthorizationService, editService);
    }

    private static GeneratedImage image(long id, long projectId) {
        GeneratedImage image = new GeneratedImage();
        image.setId(id);
        image.setProjectId(projectId);
        image.setProvider("UPLOAD");
        image.setWidth(20);
        image.setHeight(40);
        image.setFolderId(4L);
        image.setCreatedAt(LocalDateTime.now());
        return image;
    }

    @Test
    @DisplayName("メンバー/adminは編集でき、新しい画像の詳細を返す")
    void 編集できる() {
        GeneratedImage src = image(1L, 7L);
        GeneratedImage created = image(2L, 7L);
        ImageCropRegion crop = new ImageCropRegion(0, 0, 5, 5);
        when(repository.findById(1L)).thenReturn(Optional.of(src));
        when(editService.edit(src, List.of(ImageEditOperation.ROTATE_CW), crop)).thenReturn(created);

        GeneratedImageDetailResponse response = controller.edit(
                1L, new EditGeneratedImageRequest(List.of(ImageEditOperation.ROTATE_CW), crop));

        verify(adminAuthorizationService).requireProjectMemberOrAdminForResource(7L);
        assertEquals(2L, response.id());
        assertEquals("UPLOAD", response.provider());
        assertEquals(4L, response.folderId());
    }

    @Test
    @DisplayName("操作が省略(null)されても空の操作として扱う")
    void 操作省略() {
        GeneratedImage src = image(1L, 7L);
        ImageCropRegion crop = new ImageCropRegion(0, 0, 5, 5);
        when(repository.findById(1L)).thenReturn(Optional.of(src));
        when(editService.edit(src, List.of(), crop)).thenReturn(image(2L, 7L));

        assertEquals(2L, controller.edit(1L, new EditGeneratedImageRequest(null, crop)).id());
    }

    @Test
    @DisplayName("存在しない画像はNotFound")
    void 存在しない() {
        when(repository.findById(9L)).thenReturn(Optional.empty());

        assertThrows(GeneratedImageNotFoundException.class,
                () -> controller.edit(9L, new EditGeneratedImageRequest(List.of(ImageEditOperation.ROTATE_CW), null)));
        verifyNoInteractions(editService);
    }

    @Test
    @DisplayName("非メンバーはForbiddenで、編集は始まらない")
    void 非メンバーは403() {
        when(repository.findById(1L)).thenReturn(Optional.of(image(1L, 7L)));
        doThrow(new ForbiddenException("x")).when(adminAuthorizationService)
                .requireProjectMemberOrAdminForResource(7L);

        assertThrows(ForbiddenException.class,
                () -> controller.edit(1L, new EditGeneratedImageRequest(List.of(ImageEditOperation.ROTATE_CW), null)));
        verifyNoInteractions(editService);
    }

    @Test
    @DisplayName("引き継いだタグは応答に載り、空・読めないタグは空の一覧になる")
    void タグの応答() {
        GeneratedImage src = image(1L, 7L);
        when(repository.findById(1L)).thenReturn(Optional.of(src));
        EditGeneratedImageRequest request = new EditGeneratedImageRequest(List.of(ImageEditOperation.ROTATE_CW), null);

        for (Object[] c : new Object[][] {
                {"[\"cat\",\"sky\"]", List.of("cat", "sky")}, {null, List.of()}, {"  ", List.of()},
                {"not json", List.of()}}) {
            GeneratedImage created = image(2L, 7L);
            created.setTagsJson((String) c[0]);
            when(editService.edit(src, List.of(ImageEditOperation.ROTATE_CW), null)).thenReturn(created);

            assertEquals(c[1], controller.edit(1L, request).tags());
        }
    }
}
