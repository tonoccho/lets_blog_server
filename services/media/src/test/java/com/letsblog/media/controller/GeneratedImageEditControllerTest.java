package com.letsblog.media.controller;

import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.EditGeneratedImageRequest;
import com.letsblog.media.dto.GeneratedImageDetailResponse;
import com.letsblog.media.service.ForbiddenException;
import com.letsblog.media.service.GeneratedImageNotFoundException;
import com.letsblog.media.service.ImageAdjustment;
import com.letsblog.media.service.ImageCropRegion;
import com.letsblog.media.service.ImageEditOperation;
import com.letsblog.media.testsupport.MediaWorld;
import com.letsblog.media.testsupport.UploadImageFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * issue #1655: POST /api/generated-images/{id}/edit。
 *
 * <p>issue #1820: 編集サービス・認可・保存は実物で、元画像のファイルは一時ディレクトリに実際に置く。
 * DB(リポジトリ)・MQ・identity-serviceだけを差し替える。
 */
@DisplayName("GeneratedImageEditController(issue #1655)")
class GeneratedImageEditControllerTest {

    private final MediaWorld world = new MediaWorld();

    private GeneratedImageEditController controller;

    @BeforeEach
    void setUp() {
        world.actingAsAdmin(1L);
        controller = new GeneratedImageEditController(
                world.generatedImageRepository, world.adminAuthorizationService, world.editService);
    }

    @AfterEach
    void close() {
        world.close();
    }

    /** 40x20のPNGを実際に保存した、フォルダ4に入ったUPLOAD画像の行。 */
    private GeneratedImage source(long id, long projectId) {
        GeneratedImage image = world.existingImage(id, projectId,
                UploadImageFixtures.png(UploadImageFixtures.solid(40, 20, Color.RED, false)), "image/png");
        image.setProvider("UPLOAD");
        image.setFolderId(4L);
        return image;
    }

    @Test
    @DisplayName("メンバー/adminは編集でき、新しい画像の詳細を返す")
    void 編集できる() {
        source(1L, 7L);
        ImageCropRegion crop = new ImageCropRegion(0, 0, 5, 5);

        GeneratedImageDetailResponse response = controller.edit(
                1L, new EditGeneratedImageRequest(List.of(ImageEditOperation.ROTATE_CW), crop, null));

        assertTrue(response.id() != 1L, "元の画像ではなく新しい画像");
        assertEquals("UPLOAD", response.provider());
        assertEquals(4L, response.folderId());
        assertEquals(7L, response.projectId());
        assertEquals(2, world.storedFileCount(), "元のファイルと編集結果のファイル");
    }

    @Test
    @DisplayName("操作が省略(null)されても空の操作として扱う")
    void 操作省略() {
        source(1L, 7L);
        ImageCropRegion crop = new ImageCropRegion(0, 0, 5, 5);

        GeneratedImageDetailResponse response = controller.edit(1L, new EditGeneratedImageRequest(null, crop, null));

        assertEquals(5, response.width());
        assertEquals(5, response.height());
    }

    @Test
    @DisplayName("存在しない画像はNotFound")
    void 存在しない() {
        when(world.generatedImageRepository.findById(9L)).thenReturn(Optional.empty());

        assertThrows(GeneratedImageNotFoundException.class,
                () -> controller.edit(9L, new EditGeneratedImageRequest(List.of(ImageEditOperation.ROTATE_CW), null, null)));
        assertTrue(world.savedImages.isEmpty());
    }

    @Test
    @DisplayName("非メンバーはForbiddenで、編集は始まらない")
    void 非メンバーは403() {
        source(1L, 7L);
        world.actingAsUser(10L);
        world.http.json("GET", "/api/internal/identity/projects/7/members/10", "false");

        assertThrows(ForbiddenException.class,
                () -> controller.edit(1L, new EditGeneratedImageRequest(List.of(ImageEditOperation.ROTATE_CW), null, null)));
        assertTrue(world.savedImages.isEmpty());
        assertEquals(1, world.storedFileCount(), "編集結果のファイルは作られない");
    }

    @Test
    @DisplayName("プロジェクトのメンバーはadminでなくても編集できる")
    void メンバーは編集できる() {
        source(1L, 7L);
        world.actingAsUser(10L);
        world.http.json("GET", "/api/internal/identity/projects/7/members/10", "true");

        GeneratedImageDetailResponse response = controller.edit(
                1L, new EditGeneratedImageRequest(List.of(ImageEditOperation.ROTATE_CW), null, null));

        assertEquals(20, response.width());
        assertEquals(40, response.height());
    }

    @Test
    @DisplayName("引き継いだタグは応答に載り、空・読めないタグは空の一覧になる")
    void タグの応答() {
        GeneratedImage src = source(1L, 7L);
        EditGeneratedImageRequest request = new EditGeneratedImageRequest(List.of(ImageEditOperation.ROTATE_CW), null, null);

        for (Object[] c : new Object[][] {
                {"[\"cat\",\"sky\"]", List.of("cat", "sky")}, {null, List.of()}, {"  ", List.of()},
                {"not json", List.of()}}) {
            src.setTagsJson((String) c[0]);

            assertEquals(c[1], controller.edit(1L, request).tags());
        }
    }

    @Test
    @DisplayName("明るさ・コントラストの調整は編集結果に反映される")
    void 調整を渡す() {
        source(1L, 7L);

        GeneratedImageDetailResponse response = controller.edit(
                1L, new EditGeneratedImageRequest(null, null, new ImageAdjustment(30, -20)));

        assertEquals(40, response.width());
        assertEquals(20, response.height());
        assertEquals(2, world.storedFileCount());
    }
}
