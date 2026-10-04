package com.letsblog.media.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.GeneratedImageStorageService;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.BulkDeleteGeneratedImagesRequest;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import com.letsblog.media.dto.GeneratedImageBulkDeleteResponse;
import com.letsblog.media.dto.GeneratedImageDetailResponse;
import com.letsblog.media.dto.GeneratedImageSummaryResponse;
import com.letsblog.media.dto.UpdateGeneratedImageTagsRequest;
import com.letsblog.media.messaging.DomainEventPublisher;
import com.letsblog.media.repository.GeneratedImageRepository;
import com.letsblog.media.dto.UpdateGeneratedImageFolderRequest;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.GeneratedImageFolderService;
import com.letsblog.media.service.InvalidFilterParameterException;
import com.letsblog.media.service.GeneratedImageCreationService;
import com.letsblog.media.service.ForbiddenException;
import com.letsblog.media.service.GeneratedImageNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
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
    @Mock
    private DomainEventPublisher domainEventPublisher;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private GeneratedImageCreationService generatedImageCreationService;
    @Mock
    private GeneratedImageFolderService generatedImageFolderService;

    private GeneratedImageController controller;

    @BeforeEach
    void setUp() {
        controller = new GeneratedImageController(
                generatedImageRepository, generatedImageStorageService, new ObjectMapper(), domainEventPublisher,
                adminAuthorizationService, generatedImageCreationService, generatedImageFolderService);
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
        when(generatedImageRepository.findAllByOrderByCreatedAtDescIdDesc()).thenReturn(List.of(
                buildImage(1L, "a cat", "[\"猫\",\"動物\"]"),
                buildImage(2L, "a dog", "[\"犬\"]")));

        List<GeneratedImageSummaryResponse> result = controller.list(null, null, null, null, null, null);

        assertEquals(2, result.size());
    }

    @Test
    void list_tag指定時は大文字小文字を区別せず一致する画像だけ返す() {
        when(generatedImageRepository.findAllByOrderByCreatedAtDescIdDesc()).thenReturn(List.of(
                buildImage(1L, "a cat", "[\"猫\",\"動物\"]"),
                buildImage(2L, "a dog", "[\"犬\"]")));

        List<GeneratedImageSummaryResponse> result = controller.list(null, "猫", null, null, null, null);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
        assertEquals(List.of("猫", "動物"), result.get(0).tags());
    }

    /** projectId 指定時はそのプロジェクトの画像だけを引き、メンバー判定を通す(issue #830)。 */
    @Test
    void list_projectId指定時はそのプロジェクトの画像だけを返す() {
        when(generatedImageRepository.findAllByProjectIdOrderByCreatedAtDescIdDesc(5L))
                .thenReturn(List.of(buildImage(1L, "a cat", "[\"猫\"]")));

        List<GeneratedImageSummaryResponse> result = controller.list(5L, null, null, null, null, null);

        assertEquals(1, result.size());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(5L);
    }

    @Test
    void list_タグが空文字の画像は空リストとして扱う() {
        when(generatedImageRepository.findAllByOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(buildImage(1L, "a cat", "   ")));

        List<GeneratedImageSummaryResponse> result = controller.list(null, null, null, null, null, null);

        assertEquals(List.of(), result.get(0).tags());
    }

    /** cfgScale / loraWeight は DECIMAL 列。詳細では double へ落として返す。 */
    @Test
    void get_cfgScaleとloraWeightをdoubleで返す() {
        GeneratedImage image = buildImage(1L, "a cat", null);
        image.setCfgScale(new java.math.BigDecimal("7.50"));
        image.setLoraName("anime.safetensors");
        image.setLoraWeight(new java.math.BigDecimal("0.80"));
        when(generatedImageRepository.findById(1L)).thenReturn(Optional.of(image));

        GeneratedImageDetailResponse result = controller.get(1L);

        assertEquals(7.5, result.cfgScale());
        assertEquals(0.8, result.loraWeight());
        assertEquals("anime.safetensors", result.loraName());
    }

    @Test
    void updateTags_nullを渡してもタグが解除される() {
        GeneratedImage image = buildImage(1L, "a cat", "[\"猫\"]");
        when(generatedImageRepository.findById(1L)).thenReturn(Optional.of(image));
        when(generatedImageRepository.save(any(GeneratedImage.class))).thenAnswer(inv -> inv.getArgument(0));

        GeneratedImageDetailResponse result = controller.updateTags(
                1L, new UpdateGeneratedImageTagsRequest(null));

        assertEquals(List.of(), result.tags());
        assertNull(image.getTagsJson());
    }

    @Test
    void list_タグ未設定の画像は空リストとして扱う() {
        when(generatedImageRepository.findAllByOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(buildImage(1L, "a cat", null)));

        List<GeneratedImageSummaryResponse> result = controller.list(null, null, null, null, null, null);

        assertEquals(List.of(), result.get(0).tags());
    }

    @Test
    void list_不正なJSONは空リストとして扱う() {
        when(generatedImageRepository.findAllByOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(buildImage(1L, "a cat", "not json")));

        List<GeneratedImageSummaryResponse> result = controller.list(null, null, null, null, null, null);

        assertEquals(List.of(), result.get(0).tags());
    }

    @Test
    void get_タグ込みの詳細を返す() {
        when(generatedImageRepository.findById(1L))
                .thenReturn(Optional.of(buildImage(1L, "a cat", "[\"猫\"]")));

        GeneratedImageDetailResponse result = controller.get(1L);

        assertEquals(List.of("猫"), result.tags());
    }

    /**
     * issue #1101: 生成時に実際に使ったseedと、バッチ内の位置を詳細で返す。
     * ギャラリーの「この設定で画像生成」(#294)と「この画像の設定をコピー」(#437)が
     * これを読んで同じ画像を再現する。
     */
    @Test
    void get_seedとバッチ内位置を詳細で返す() {
        GeneratedImage image = buildImage(1L, "a cat", null);
        image.setSeed(864213579L);
        image.setBatchSize(2);
        image.setBatchIndex(1);
        when(generatedImageRepository.findById(1L)).thenReturn(Optional.of(image));

        GeneratedImageDetailResponse result = controller.get(1L);

        assertEquals(864213579L, result.seed());
        assertEquals(2, result.batchSize());
        assertEquals(1, result.batchIndex());
    }

    /** issue #1601: img2imgで生成した画像は、詳細で参照元の画像IDを返す。 */
    @Test
    void get_参照元の画像IDを詳細で返す() {
        GeneratedImage image = buildImage(1L, "a cat", null);
        image.setSourceImageId(5L);
        when(generatedImageRepository.findById(1L)).thenReturn(Optional.of(image));

        GeneratedImageDetailResponse result = controller.get(1L);

        assertEquals(5L, result.sourceImageId());
    }

    /** issue #1601: 参照画像を使っていない画像の参照元はnull。 */
    @Test
    void get_参照元が無い画像はsourceImageIdがnull() {
        GeneratedImage image = buildImage(1L, "a cat", null);
        when(generatedImageRepository.findById(1L)).thenReturn(Optional.of(image));

        assertNull(controller.get(1L).sourceImageId());
    }

    /** issue #1101: CHATGPT由来の画像はseedを持たないため、詳細でもNULLのまま返る。 */
    @Test
    void get_CHATGPT由来の画像はseedがnullのまま返る() {
        GeneratedImage image = buildImage(1L, "a cat", null);
        image.setProvider("CHATGPT");
        when(generatedImageRepository.findById(1L)).thenReturn(Optional.of(image));

        GeneratedImageDetailResponse result = controller.get(1L);

        assertNull(result.seed());
        assertNull(result.batchIndex());
        assertEquals("CHATGPT", result.provider());
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

    /**
     * {@code POST /api/generated-images} は、認可を確認したうえで保存処理を
     * {@link GeneratedImageCreationService} へ委譲する(issue #583で切り出した)。
     * 保存処理そのものの検証は {@code GeneratedImageCreationServiceTest} が行う。
     */
    @Test
    void create_認可を確認してから保存サービスへ委譲する() {
        byte[] imageData = new byte[]{1, 2, 3};
        CreateGeneratedImageRequest request = new CreateGeneratedImageRequest(
                1L, "a cat", "blurry", 20, 7.0, "euler", "normal", 123L, 512, 512, 1, 0,
                "checkpoint.safetensors", null, null, "image/png", "COMFYUI", "[\"猫\"]", imageData);

        GeneratedImage saved = buildImage(42L, "a cat", "[\"猫\"]");
        saved.setProvider("COMFYUI");
        when(generatedImageCreationService.create(request)).thenReturn(saved);

        GeneratedImageDetailResponse response = controller.create(request);

        assertEquals(42L, response.id());
        assertEquals("a cat", response.prompt());
        assertEquals("COMFYUI", response.provider());
        assertEquals(List.of("猫"), response.tags());

        // ファイル保存が始まる前にプロジェクトメンバー判定を通していること(issue #830)。
        verify(adminAuthorizationService).requireProjectMemberOrAdminForResource(1L);
        verify(generatedImageCreationService).create(request);
    }

    // ---- 一括削除(issue #1492) ----

    private void stubFound(GeneratedImage... images) {
        for (GeneratedImage image : images) {
            when(generatedImageRepository.findById(image.getId())).thenReturn(Optional.of(image));
        }
    }

    @Test
    void bulkDelete_全件を認可してから実体とDB行を削除し件数を返す() {
        GeneratedImage a = buildImage(1L, "a", null);
        a.setProjectId(5L);
        GeneratedImage b = buildImage(2L, "b", null);
        b.setProjectId(6L);
        stubFound(a, b);

        GeneratedImageBulkDeleteResponse result =
                controller.bulkDelete(new BulkDeleteGeneratedImagesRequest(List.of(1L, 2L)));

        assertEquals(2, result.deletedCount());
        assertEquals(0, result.failedCount());
        assertEquals(List.of(1L, 2L), result.deletedIds());
        assertTrue(result.failures().isEmpty());
        verify(adminAuthorizationService).requireProjectMemberOrAdminForResource(5L);
        verify(adminAuthorizationService).requireProjectMemberOrAdminForResource(6L);
        verify(generatedImageStorageService).delete("path/1.png");
        verify(generatedImageStorageService).delete("path/2.png");
        verify(generatedImageRepository).delete(a);
        verify(generatedImageRepository).delete(b);
    }

    /** 権限の無い id が1つでもあれば、権限のある画像も含めて1件も削除しない(ファイル削除は取り消せない)。 */
    @Test
    void bulkDelete_権限の無いidが含まれると何も削除せずForbiddenになる() {
        GeneratedImage allowed = buildImage(1L, "a", null);
        allowed.setProjectId(5L);
        GeneratedImage denied = buildImage(2L, "b", null);
        denied.setProjectId(6L);
        stubFound(allowed, denied);
        // 権限のある画像側の判定は通る(厳格スタブのため、呼ばれる引数ごとに宣言する)。
        doNothing().when(adminAuthorizationService).requireProjectMemberOrAdminForResource(5L);
        doThrow(new ForbiddenException("not a member"))
                .when(adminAuthorizationService).requireProjectMemberOrAdminForResource(6L);

        assertThrows(ForbiddenException.class,
                () -> controller.bulkDelete(new BulkDeleteGeneratedImagesRequest(List.of(1L, 2L))));

        verifyNoInteractions(generatedImageStorageService);
        verify(generatedImageRepository, never()).delete(any(GeneratedImage.class));
    }

    @Test
    void bulkDelete_存在しないidが含まれると何も削除せず404になる() {
        GeneratedImage a = buildImage(1L, "a", null);
        stubFound(a);
        when(generatedImageRepository.findById(9L)).thenReturn(Optional.empty());

        assertThrows(GeneratedImageNotFoundException.class,
                () -> controller.bulkDelete(new BulkDeleteGeneratedImagesRequest(List.of(1L, 9L))));

        verifyNoInteractions(generatedImageStorageService);
        verify(generatedImageRepository, never()).delete(any(GeneratedImage.class));
    }

    /** 個別に失敗した画像は failures に載り、残りの削除は続行される(DB行は失敗した画像では消さない)。 */
    @Test
    void bulkDelete_個別の削除失敗はfailuresに載り残りは続行する() {
        GeneratedImage a = buildImage(1L, "a", null);
        GeneratedImage b = buildImage(2L, "b", null);
        stubFound(a, b);
        doThrow(new RuntimeException("disk error")).when(generatedImageStorageService).delete("path/1.png");

        GeneratedImageBulkDeleteResponse result =
                controller.bulkDelete(new BulkDeleteGeneratedImagesRequest(List.of(1L, 2L)));

        assertEquals(1, result.deletedCount());
        assertEquals(1, result.failedCount());
        assertEquals(List.of(2L), result.deletedIds());
        assertEquals("disk error", result.failures().get("1"));
        verify(generatedImageRepository, never()).delete(a);
        verify(generatedImageRepository).delete(b);
    }

    @Test
    void bulkDelete_重複したidは1回だけ処理する() {
        GeneratedImage a = buildImage(1L, "a", null);
        stubFound(a);

        GeneratedImageBulkDeleteResponse result =
                controller.bulkDelete(new BulkDeleteGeneratedImagesRequest(List.of(1L, 1L)));

        assertEquals(1, result.deletedCount());
        verify(generatedImageStorageService).delete("path/1.png");
    }

    @Test
    void bulkDelete_例外メッセージがnullでも失敗として記録する() {
        GeneratedImage a = buildImage(1L, "a", null);
        stubFound(a);
        doThrow(new RuntimeException()).when(generatedImageStorageService).delete("path/1.png");

        GeneratedImageBulkDeleteResponse result =
                controller.bulkDelete(new BulkDeleteGeneratedImagesRequest(List.of(1L)));

        assertEquals(0, result.deletedCount());
        assertEquals(1, result.failedCount());
        assertEquals("null", result.failures().get("1"));
    }

    // ---- issue #1493: フォルダ ----

    private GeneratedImage inFolder(Long id, Long folderId) {
        GeneratedImage image = buildImage(id, "p" + id, null);
        image.setFolderId(folderId);
        return image;
    }

    @Test
    void list_folderId指定時はそのフォルダと子孫の画像だけ返す() {
        when(generatedImageFolderService.descendantIdsIncludingSelf(1L)).thenReturn(java.util.Set.of(1L, 2L));
        when(generatedImageRepository.findAllByOrderByCreatedAtDescIdDesc()).thenReturn(List.of(
                inFolder(10L, 1L), inFolder(11L, 2L), inFolder(12L, 3L), inFolder(13L, null)));

        List<GeneratedImageSummaryResponse> result = controller.list(null, null, null, null, 1L, null);

        assertEquals(List.of(10L, 11L), result.stream().map(GeneratedImageSummaryResponse::id).toList());
        assertEquals(1L, result.get(0).folderId());
    }

    @Test
    void list_unfiled指定時はフォルダに属さない画像だけ返す() {
        when(generatedImageRepository.findAllByOrderByCreatedAtDescIdDesc()).thenReturn(List.of(
                inFolder(10L, 1L), inFolder(13L, null)));

        List<GeneratedImageSummaryResponse> result = controller.list(null, null, null, null, null, true);

        assertEquals(List.of(13L), result.stream().map(GeneratedImageSummaryResponse::id).toList());
        assertNull(result.get(0).folderId());
    }

    @Test
    void list_unfiledがfalseなら絞り込まない() {
        when(generatedImageRepository.findAllByOrderByCreatedAtDescIdDesc()).thenReturn(List.of(
                inFolder(10L, 1L), inFolder(13L, null)));

        assertEquals(2, controller.list(null, null, null, null, null, false).size());
    }

    @Test
    void list_folderIdとunfiledの同時指定は拒否する() {
        assertThrows(InvalidFilterParameterException.class,
                () -> controller.list(null, null, null, null, 1L, true));
    }

    @Test
    void list_フォルダ絞り込みはDBでページングせず絞った後に切る() {
        when(generatedImageFolderService.descendantIdsIncludingSelf(1L)).thenReturn(java.util.Set.of(1L));
        when(generatedImageRepository.findAllByOrderByCreatedAtDescIdDesc()).thenReturn(List.of(
                inFolder(10L, 2L), inFolder(11L, 1L), inFolder(12L, 1L), inFolder(13L, 1L)));

        List<GeneratedImageSummaryResponse> result = controller.list(null, null, 1, 1, 1L, null);

        assertEquals(List.of(12L), result.stream().map(GeneratedImageSummaryResponse::id).toList());
    }

    @Test
    void updateFolder_adminでなければ画像を読まずに拒否する() {
        doThrow(new ForbiddenException("admin")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller.updateFolder(1L, new UpdateGeneratedImageFolderRequest(2L)));

        verifyNoInteractions(generatedImageRepository, generatedImageFolderService);
    }

    @Test
    void updateFolder_フォルダを設定して保存する() {
        GeneratedImage image = buildImage(1L, "a", null);
        when(generatedImageRepository.findById(1L)).thenReturn(Optional.of(image));
        when(generatedImageRepository.save(any(GeneratedImage.class))).thenAnswer(inv -> inv.getArgument(0));

        GeneratedImageDetailResponse result = controller.updateFolder(1L, new UpdateGeneratedImageFolderRequest(2L));

        verify(adminAuthorizationService).requireAdmin();
        verify(generatedImageFolderService).requireExists(2L);
        assertEquals(2L, result.folderId());
    }

    @Test
    void updateFolder_nullなら未分類へ戻しフォルダの存在確認はしない() {
        GeneratedImage image = inFolder(1L, 2L);
        when(generatedImageRepository.findById(1L)).thenReturn(Optional.of(image));
        when(generatedImageRepository.save(any(GeneratedImage.class))).thenAnswer(inv -> inv.getArgument(0));

        GeneratedImageDetailResponse result = controller.updateFolder(1L, new UpdateGeneratedImageFolderRequest(null));

        assertNull(result.folderId());
        verifyNoInteractions(generatedImageFolderService);
    }

    @Test
    void updateFolder_画像が無ければ404相当の例外() {
        when(generatedImageRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(GeneratedImageNotFoundException.class,
                () -> controller.updateFolder(1L, new UpdateGeneratedImageFolderRequest(2L)));
    }
}
