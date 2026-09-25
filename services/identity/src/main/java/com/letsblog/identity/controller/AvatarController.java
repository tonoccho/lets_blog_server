package com.letsblog.identity.controller;

import com.letsblog.identity.dto.UserProfileResponse;
import com.letsblog.identity.service.AdminAuthorizationService;
import com.letsblog.identity.service.AvatarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * issue #1241: プロフィール編集画面のアバター画像アップロード/配信。
 *
 * <p>認可は要件8の通り、既存の{@code PUT /api/users/{id}}({@link UserController#updateProfile})と
 * 同じ{@code requireSelfOrAdmin}に揃える。アップロード(書き込み)だけでなく配信(読み込み)も
 * 同じ認可にしているのは、アバター画像も本人のプロフィール情報の一部であり、
 * このIssueのスコープ(プロフィール編集画面)が本人/admin以外への公開を要求していないため。
 */
@Tag(name = "Users", description = "ユーザー管理API")
@RestController
@RequestMapping("/api/users")
public class AvatarController {

    private final AvatarService avatarService;
    private final AdminAuthorizationService adminAuthorizationService;

    public AvatarController(AvatarService avatarService, AdminAuthorizationService adminAuthorizationService) {
        this.avatarService = avatarService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @Operation(summary = "アバター画像をアップロード",
            description = "プロフィール編集画面でクライアント側(Canvas)で切り抜いた正方形画像をアップロードします。"
                    + "image/jpeg・image/png・image/webpのみ受理し、サーバー側で512x512へ変換のうえメタ情報を削除します。")
    @ApiResponse(responseCode = "200", description = "アバターが更新されました")
    @ApiResponse(responseCode = "400", description = "対応していない画像形式、または画像として読み込めません")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "本人またはadmin権限がありません")
    @ApiResponse(responseCode = "404", description = "ユーザーが見つかりません")
    @ApiResponse(responseCode = "413", description = "アップロードサイズが上限(20MB)を超えています")
    @PostMapping(value = "/{id}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserProfileResponse uploadAvatar(
            @Parameter(description = "ユーザーID") @PathVariable Long id,
            @RequestPart("file") MultipartFile file) {
        adminAuthorizationService.requireSelfOrAdmin(id);
        try {
            return avatarService.uploadAvatar(id, file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("アップロードされたファイルの読み込みに失敗しました", e);
        }
    }

    @Operation(summary = "アバター画像を取得", description = "アップロード済みのアバター画像(JPEG、512x512)を返します。")
    @ApiResponse(responseCode = "200", description = "アバター画像を返す")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "本人またはadmin権限がありません")
    @ApiResponse(responseCode = "404", description = "ユーザーが見つからない、またはアバター未設定")
    @GetMapping(value = "/{id}/avatar", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> getAvatar(@Parameter(description = "ユーザーID") @PathVariable Long id) {
        adminAuthorizationService.requireSelfOrAdmin(id);
        byte[] bytes = avatarService.loadAvatar(id);
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.IMAGE_JPEG)
                // 差し替え(AC5)後も同一URLのまま配信するため、ブラウザ/中間キャッシュに
                // 古い画像を返させない。
                .cacheControl(CacheControl.noStore())
                .body(bytes);
    }
}
