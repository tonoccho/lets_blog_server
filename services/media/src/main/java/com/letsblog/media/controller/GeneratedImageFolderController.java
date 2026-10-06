package com.letsblog.media.controller;

import com.letsblog.media.domain.GeneratedImageFolder;
import com.letsblog.media.dto.CreateGeneratedImageFolderRequest;
import com.letsblog.media.dto.GeneratedImageFolderDeleteImpactResponse;
import com.letsblog.media.dto.GeneratedImageFolderResponse;
import com.letsblog.media.dto.UpdateGeneratedImageFolderNameRequest;
import com.letsblog.media.dto.UpdateGeneratedImageFolderParentRequest;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.GeneratedImageFolderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 生成画像の入れ子フォルダ(issue #1493)。フォルダは横断の共通ツリーで、作成・親の変更・改名・削除は管理者のみ、
 * 一覧(ツリー)の取得は認証済みの全利用者に許す。パスは既存のgatewayルート
 * {@code /api/generated-images/**}に収まる。
 */
@RestController
public class GeneratedImageFolderController {

    private final GeneratedImageFolderService folderService;
    private final AdminAuthorizationService adminAuthorizationService;

    public GeneratedImageFolderController(GeneratedImageFolderService folderService,
                                          AdminAuthorizationService adminAuthorizationService) {
        this.folderService = folderService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    // メソッド名を list/create にしないのは、OpenAPIのoperationIdが他コントローラの list/create と
    // 衝突して連番(list_1 → list_2)が振り直され、生成APIクライアントの既存名が変わるため。
    /** フォルダ一覧(フラット。親子は{@code parentId}で表す)。画像の件数や情報は返さない。 */
    @GetMapping("/api/generated-images/folders")
    public List<GeneratedImageFolderResponse> listFolders() {
        adminAuthorizationService.requireAuthenticated();
        return folderService.list().stream().map(GeneratedImageFolderController::toResponse).toList();
    }

    @PostMapping("/api/generated-images/folders")
    @ResponseStatus(HttpStatus.CREATED)
    public GeneratedImageFolderResponse createFolder(@Valid @RequestBody CreateGeneratedImageFolderRequest request) {
        adminAuthorizationService.requireAdmin();
        return toResponse(folderService.create(request.name(), request.parentId()));
    }

    /** 親の変更。自分自身または子孫を親に指定すると409で拒否する。 */
    @PutMapping("/api/generated-images/folders/{id}/parent")
    public GeneratedImageFolderResponse changeParent(
            @PathVariable Long id, @RequestBody UpdateGeneratedImageFolderParentRequest request) {
        adminAuthorizationService.requireAdmin();
        return toResponse(folderService.changeParent(id, request.parentId()));
    }

    /** 改名(issue #1494)。同じ親の下の重複名は作成時と同じく検査しない。 */
    @PutMapping("/api/generated-images/folders/{id}/name")
    public GeneratedImageFolderResponse renameFolder(
            @PathVariable Long id, @Valid @RequestBody UpdateGeneratedImageFolderNameRequest request) {
        adminAuthorizationService.requireAdmin();
        return toResponse(folderService.rename(id, request.name()));
    }

    /** 削除の影響範囲(子孫フォルダ数・未分類に戻る画像の枚数)。確認ダイアログ用で、何も変更しない(issue #1494)。 */
    @GetMapping("/api/generated-images/folders/{id}/delete-impact")
    public GeneratedImageFolderDeleteImpactResponse folderDeleteImpact(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return folderService.deleteImpact(id);
    }

    /** フォルダと子孫を削除する。所属画像は削除せず未分類へ戻す(issue #1494)。 */
    @DeleteMapping("/api/generated-images/folders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFolder(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        folderService.delete(id);
    }

    private static GeneratedImageFolderResponse toResponse(GeneratedImageFolder folder) {
        return new GeneratedImageFolderResponse(folder.getId(), folder.getName(), folder.getParentId());
    }
}
