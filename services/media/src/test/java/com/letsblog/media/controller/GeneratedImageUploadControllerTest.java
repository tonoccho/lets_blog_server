package com.letsblog.media.controller;

import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.GeneratedImageDetailResponse;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.ForbiddenException;
import com.letsblog.media.service.GeneratedImageUploadService;
import com.letsblog.media.service.InvalidImageUploadException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** issue #1599: POST /api/generated-images/upload。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GeneratedImageUploadController(issue #1599)")
class GeneratedImageUploadControllerTest {

    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private GeneratedImageUploadService uploadService;

    private GeneratedImageUploadController controller;

    @BeforeEach
    void setUp() {
        controller = new GeneratedImageUploadController(adminAuthorizationService, uploadService);
    }

    @Test
    @DisplayName("メンバー/adminならアップロードでき、UPLOADの詳細を返す")
    void アップロードできる() {
        GeneratedImage saved = new GeneratedImage();
        saved.setId(5L);
        saved.setProjectId(7L);
        saved.setProvider("UPLOAD");
        saved.setWidth(1920);
        saved.setHeight(1080);
        saved.setCreatedAt(LocalDateTime.now());
        when(uploadService.upload(7L, new byte[] {1, 2})).thenReturn(saved);

        GeneratedImageDetailResponse response = controller.upload(
                7L, new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[] {1, 2}));

        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
        assertEquals(5L, response.id());
        assertEquals("UPLOAD", response.provider());
        assertNull(response.prompt());
        assertEquals(1920, response.width());
    }

    @Test
    @DisplayName("非メンバーはForbiddenで、ファイルは処理されない")
    void 非メンバーは403() {
        doThrow(new ForbiddenException("x")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        assertThrows(ForbiddenException.class, () -> controller.upload(
                7L, new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[] {1})));
        verifyNoInteractions(uploadService);
    }

    @Test
    @DisplayName("ファイルが空なら拒否される")
    void 空ファイルは拒否() {
        assertThrows(InvalidImageUploadException.class, () -> controller.upload(
                7L, new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[0])));
        verifyNoInteractions(uploadService);
    }

    @Test
    @DisplayName("サービスの拒否はそのまま伝わる")
    void サービスの拒否は伝わる() {
        when(uploadService.upload(any(), any())).thenThrow(new InvalidImageUploadException("bad"));

        assertThrows(InvalidImageUploadException.class, () -> controller.upload(
                7L, new MockMultipartFile("file", "a.gif", "image/gif", new byte[] {1})));
    }
}
