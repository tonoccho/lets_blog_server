package com.letsblog.media.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.GeneratedImageStorageService;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.BulkDeleteGeneratedImagesRequest;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import com.letsblog.media.dto.GeneratedImageBulkDeleteResponse;
import com.letsblog.media.dto.GeneratedImageDetailResponse;
import com.letsblog.media.dto.GeneratedImageSummaryResponse;
import com.letsblog.media.dto.UpdateGeneratedImageFolderRequest;
import com.letsblog.media.dto.UpdateGeneratedImageTagsRequest;
import com.letsblog.media.dto.UtcDateTimes;
import com.letsblog.media.messaging.DomainEventPublisher;
import com.letsblog.media.repository.GeneratedImageRepository;
import com.letsblog.media.repository.OffsetLimitPageable;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.GeneratedImageCreationService;
import com.letsblog.media.service.GeneratedImageFolderService;
import com.letsblog.media.service.GeneratedImageNotFoundException;
import com.letsblog.media.service.InvalidFilterParameterException;
import com.letsblog.media.service.InvalidPagingParameterException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ComfyUIで生成した画像とパラメータの一覧・詳細・バイナリ取得(Web管理画面のギャラリー表示用)。
 */
@RestController
public class GeneratedImageController {

    private static final Logger log = LoggerFactory.getLogger(GeneratedImageController.class);

    /**
     * 一覧の{@code limit}の上限(issue #1472)。ギャラリーのページサイズ(24)の4倍強で、1回の応答を
     * 数百KBに抑えつつ、画面のページサイズの変更に余裕を残す値。超過は切り詰めず400にする:
     * 切り詰めると「返った件数がlimit未満なら終端」という呼び出し側の判定が誤るため。
     */
    static final int MAX_LIMIT = 100;

    private final GeneratedImageRepository generatedImageRepository;
    private final GeneratedImageStorageService generatedImageStorageService;
    private final ObjectMapper objectMapper;
    private final DomainEventPublisher domainEventPublisher;
    private final AdminAuthorizationService adminAuthorizationService;
    private final GeneratedImageCreationService generatedImageCreationService;
    private final GeneratedImageFolderService generatedImageFolderService;

    public GeneratedImageController(GeneratedImageRepository generatedImageRepository,
                                     GeneratedImageStorageService generatedImageStorageService,
                                     ObjectMapper objectMapper,
                                     DomainEventPublisher domainEventPublisher,
                                     AdminAuthorizationService adminAuthorizationService,
                                     GeneratedImageCreationService generatedImageCreationService,
                                     GeneratedImageFolderService generatedImageFolderService) {
        this.generatedImageRepository = generatedImageRepository;
        this.generatedImageStorageService = generatedImageStorageService;
        this.objectMapper = objectMapper;
        this.domainEventPublisher = domainEventPublisher;
        this.adminAuthorizationService = adminAuthorizationService;
        this.generatedImageCreationService = generatedImageCreationService;
        this.generatedImageFolderService = generatedImageFolderService;
    }

    /**
     * 一覧。tag指定時は、そのタグを持つ画像だけに絞り込む(大文字小文字を区別しない完全一致、issue #281)。
     *
     * <p>{@code limit}/{@code offset}は省略可能(issue #1472)。{@code limit}を省略すると従来どおり
     * 全件を返す({@code offset}だけ指定した場合はその件数を飛ばした残りを返す)。{@code limit}指定時は
     * createdAt降順・同時刻はid降順の並びで{@code offset}件を飛ばした位置から最大{@code limit}件を返し、
     * tag指定時は絞り込んだ後の一覧に適用する。レスポンスは常にJSON配列のまま。
     *
     * <p>{@code folderId}指定時は、そのフォルダと子孫フォルダに属する画像だけ、{@code unfiled=true}指定時は
     * どのフォルダにも属さない画像だけに絞り込む(issue #1493)。同時指定は400。タグ絞り込み・ページング・
     * projectIdと併用でき、画像の認可は変わらない(絞り込みは認可の範囲内の画像をさらに狭めるだけ)。
     */
    @GetMapping("/api/generated-images")
    public List<GeneratedImageSummaryResponse> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Long folderId,
            @RequestParam(required = false) Boolean unfiled) {
        // projectId 指定時はそのプロジェクトのメンバーに限定する(issue #830)。
        // 未指定は「全プロジェクトの生成画像を返す」なので admin に限定する。本来は
        // 「操作者が所属するプロジェクトの分だけ」返すべきだが、所属プロジェクトの一覧を
        // 引く手段が media-service に無い(内部ブリッジは isProjectMember だけ)。
        // #583 で project_users が project-service へ移った後に絞り込みへ置き換える。
        if (projectId != null) {
            adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        } else {
            adminAuthorizationService.requireAdmin();
        }
        validatePaging(limit, offset);
        boolean unfiledOnly = Boolean.TRUE.equals(unfiled);
        if (folderId != null && unfiledOnly) {
            throw new InvalidFilterParameterException("folderIdとunfiledは同時に指定できません");
        }
        Set<Long> folderIds = folderId == null ? null : generatedImageFolderService.descendantIdsIncludingSelf(folderId);
        long skip = offset == null ? 0 : offset;
        // tagはtags_json(TEXT列)の中身なのでDBでは絞れない。フォルダ絞り込みも、子孫を解決した
        // idの集合でここで絞る。tag・フォルダ絞り込み無しでlimit指定のときだけDBで切り、
        // それ以外は全行を読んで絞り込んだ後にメモリ上で切る(絞り込み → ページングの順を守る)。
        boolean pagedInDatabase = tag == null && folderIds == null && !unfiledOnly && limit != null;
        List<GeneratedImage> images = findImages(projectId, pagedInDatabase ? new OffsetLimitPageable(skip, limit) : null);
        var summaries = images.stream()
                .filter(image -> folderIds == null
                        || (image.getFolderId() != null && folderIds.contains(image.getFolderId())))
                .filter(image -> !unfiledOnly || image.getFolderId() == null)
                .map(image -> new GeneratedImageSummaryResponse(
                        image.getId(), image.getProjectId(), image.getPrompt(),
                        image.getCheckpoint(), UtcDateTimes.toInstant(image.getCreatedAt()), parseTags(image.getTagsJson()),
                        image.getProvider(), image.getFolderId()))
                .filter(response -> tag == null || response.tags().stream().anyMatch(t -> t.equalsIgnoreCase(tag)));
        if (!pagedInDatabase) {
            summaries = summaries.skip(skip);
            if (limit != null) {
                summaries = summaries.limit(limit);
            }
        }
        return summaries.toList();
    }

    private List<GeneratedImage> findImages(Long projectId, OffsetLimitPageable pageable) {
        if (projectId != null) {
            return pageable != null
                    ? generatedImageRepository.findAllByProjectIdOrderByCreatedAtDescIdDesc(projectId, pageable)
                    : generatedImageRepository.findAllByProjectIdOrderByCreatedAtDescIdDesc(projectId);
        }
        return pageable != null
                ? generatedImageRepository.findAllByOrderByCreatedAtDescIdDesc(pageable)
                : generatedImageRepository.findAllByOrderByCreatedAtDescIdDesc();
    }

    private static void validatePaging(Integer limit, Integer offset) {
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            throw new InvalidPagingParameterException("limitは1以上" + MAX_LIMIT + "以下で指定してください");
        }
        if (offset != null && offset < 0) {
            throw new InvalidPagingParameterException("offsetは0以上で指定してください");
        }
    }

    @GetMapping("/api/generated-images/{id}")
    public GeneratedImageDetailResponse get(@PathVariable Long id) {
        return toDetailResponse(findAuthorized(id));
    }

    /**
     * legacy-apiの{@code AiAssistService#generateImage}が、実際の画像生成(ComfyUI/ChatGPT呼び出し、
     * 引き続きlegacy-api側で行う)の後に呼ぶ(issue #573 stage4)。ファイル保存とDB行作成を
     * まとめて行う。
     */
    @PostMapping("/api/generated-images")
    @ResponseStatus(HttpStatus.CREATED)
    public GeneratedImageDetailResponse create(@Valid @RequestBody CreateGeneratedImageRequest request) {
        // 指定されたプロジェクトに画像を登録できるのはそのメンバー(またはadmin)だけ(issue #830)。
        // ファイル保存が始まる前に判定する。
        adminAuthorizationService.requireProjectMemberOrAdminForResource(request.projectId());
        // 保存処理そのものは GeneratedImageCreationService へ切り出した(issue #583)。
        // #583で画像生成本体がmedia-serviceへ移り、ImageGenerationService からも
        // 同じ処理を直接呼ぶ必要が生じたため。
        return toDetailResponse(generatedImageCreationService.create(request));
    }

    /** 自動生成されたタグを手動で編集・追加する(issue #281)。 */
    @PutMapping("/api/generated-images/{id}/tags")
    public GeneratedImageDetailResponse updateTags(
            @PathVariable Long id, @RequestBody UpdateGeneratedImageTagsRequest request) {
        GeneratedImage image = findAuthorized(id);
        image.setTagsJson(serializeTags(request.tags()));
        return toDetailResponse(generatedImageRepository.save(image));
    }

    /**
     * 画像を1つのフォルダへ入れる、または未分類へ戻す(issue #1493)。管理者のみ。{@code folderId}がnullなら未分類。
     * ファイルの物理配置は変えない(DB上の論理分類)。
     */
    @PutMapping("/api/generated-images/{id}/folder")
    public GeneratedImageDetailResponse updateFolder(
            @PathVariable Long id, @RequestBody UpdateGeneratedImageFolderRequest request) {
        adminAuthorizationService.requireAdmin();
        GeneratedImage image = findOrThrow(id);
        if (request.folderId() != null) {
            generatedImageFolderService.requireExists(request.folderId());
        }
        image.setFolderId(request.folderId());
        return toDetailResponse(generatedImageRepository.save(image));
    }

    @GetMapping("/api/generated-images/{id}/file")
    public ResponseEntity<byte[]> getImageFile(@PathVariable Long id) {
        GeneratedImage image = findAuthorized(id);
        byte[] data = generatedImageStorageService.load(image.getFilePath());
        // 保存しているMIMEで返す(アップロードされたJPEGはimage/jpeg、それ以外は従来どおりPNG。issue #1599)。
        boolean jpeg = MediaType.IMAGE_JPEG_VALUE.equalsIgnoreCase(image.getMimeType());
        return ResponseEntity.ok()
                .contentType(jpeg ? MediaType.IMAGE_JPEG : MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + id + (jpeg ? ".jpg" : ".png"))
                .body(data);
    }

    @DeleteMapping("/api/generated-images/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        GeneratedImage image = findAuthorized(id);
        generatedImageStorageService.delete(image.getFilePath());
        generatedImageRepository.delete(image);
        return ResponseEntity.noContent().build();
    }

    /**
     * 複数の生成画像をまとめて物理削除する(issue #1492)。同期処理。
     *
     * <p>ファイル削除は取り消せないため、<b>先に全idを読んで全件の認可を通し</b>、そのあとで削除する。
     * 権限の無い画像(403)や存在しない画像(404)が1つでも含まれていれば、権限のある画像も含めて
     * 1件も削除しない。認可を通ったあとの個別の削除失敗は{@code failures}に載せて残りを続行する。
     */
    @PostMapping("/api/generated-images/bulk-delete")
    public GeneratedImageBulkDeleteResponse bulkDelete(@Valid @RequestBody BulkDeleteGeneratedImagesRequest request) {
        List<GeneratedImage> images = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(request.imageIds())) {
            images.add(findAuthorized(id));
        }

        List<Long> deletedIds = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (GeneratedImage image : images) {
            try {
                generatedImageStorageService.delete(image.getFilePath());
                generatedImageRepository.delete(image);
                deletedIds.add(image.getId());
            } catch (RuntimeException e) {
                log.warn("生成画像の一括削除で個別の削除に失敗しました(id={}): {}", image.getId(), e.getMessage());
                failures.put(String.valueOf(image.getId()), String.valueOf(e.getMessage()));
            }
        }
        return new GeneratedImageBulkDeleteResponse(deletedIds.size(), failures.size(), deletedIds, failures);
    }

    private GeneratedImageDetailResponse toDetailResponse(GeneratedImage image) {
        return new GeneratedImageDetailResponse(
                image.getId(), image.getProjectId(), image.getPrompt(), image.getNegativePrompt(),
                image.getSteps(), image.getCfgScale() != null ? image.getCfgScale().doubleValue() : null,
                image.getSamplerName(), image.getScheduler(), image.getSeed(),
                image.getWidth(), image.getHeight(), image.getBatchSize(), image.getBatchIndex(),
                image.getCheckpoint(),
                image.getLoraName(), image.getLoraWeight() != null ? image.getLoraWeight().doubleValue() : null,
                UtcDateTimes.toInstant(image.getCreatedAt()), parseTags(image.getTagsJson()), image.getProvider(),
                image.getFolderId());
    }

    /**
     * IDで生成画像を引き、その所属プロジェクトのメンバー(またはadmin)であることを確かめる
     * (issue #830)。所属を調べるには一度読む必要があるため、存在確認と認可をここでまとめる。
     */
    private GeneratedImage findAuthorized(Long id) {
        GeneratedImage image = findOrThrow(id);
        adminAuthorizationService.requireProjectMemberOrAdminForResource(image.getProjectId());
        return image;
    }

    private GeneratedImage findOrThrow(Long id) {
        return generatedImageRepository.findById(id)
                .orElseThrow(() -> new GeneratedImageNotFoundException("id: " + id));
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("タグ情報のパースに失敗しました(空のタグ一覧として扱います): {}", e.getMessage());
            return List.of();
        }
    }

    private String serializeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            log.warn("タグ情報のシリアライズに失敗しました: {}", e.getMessage());
            return null;
        }
    }
}
