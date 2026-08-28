package com.letsblog.media.client;

import com.letsblog.common.client.ServiceAuthHeaders;
import com.letsblog.common.client.SyncCallProfile;
import com.letsblog.common.client.SyncServiceClient;
import com.letsblog.common.client.SyncServiceException;
import com.letsblog.media.service.CmsBridgeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * publishing-serviceの内部CMSブリッジ(/api/internal/publishing/**、issue #573 stage3でlegacy-apiに
 * {@code /api/internal/cms/**}として新設、issue #709でpublishing-serviceへ移管し、レビュー指摘対応で
 * 他の内部ブリッジと同じ{@code /api/internal/{owning-service}/**}命名規則に合わせて現行パスへ変更、
 * Epic #551 C6-3、{@code com.letsblog.publishing.controller.CmsMediaBridgeController})を呼び出す
 * クライアント。issue #581
 * (C12)でlbs-commonの{@link SyncServiceClient}(タイムアウト・リトライ・サーキットブレーカーの
 * 共通実装)へ移行した(接続先の切り替え後もこの共通実装は維持している)。方針の詳細は
 * docs/SYNC_SERVICE_CALLS.md参照。
 *
 * <p>CMS(WordPress)への実際の接続情報({@code CmsCredentials}、SSH鍵等の秘匿情報を含む)は
 * publishing-service側に留まり、media-serviceへは一切渡らない。media-serviceは「このsite/projectに
 * 対して操作してほしい」という依頼のみを送る。
 *
 * <p>認証はstage1/stage2と同じ方式(呼び出し元ユーザーのBearerトークンをそのまま転送)だが、
 * このクライアントは同期呼び出し({@link com.letsblog.media.controller.MediaController}の
 * アップロード、{@code MediaGarbageCollectionService#scan})と非同期呼び出し
 * ({@code MediaGarbageCollectionJobRunner}の削除ループ、バックグラウンドスレッド)の両方から
 * 使われるため、{@code HttpServletRequest}注入ではなく、呼び出し元が明示的に渡した
 * トークン文字列を引数として受け取る統一インターフェースにしている
 * ({@link GenerationJobClient}と同じ理由・パターン)。
 *
 * <p>フォールバック方針: いずれのメソッドも明確なエラー({@link CmsBridgeException}）として
 * 呼び出し元へ伝播させる(CMS操作の成否を呼び出し元が黙って見誤らないようにするため。
 * media-serviceのメディアアップロード/GC操作は、失敗したことをユーザー/バッチ処理へ確実に
 * 伝える必要がある)。
 */
@Component
public class CmsBridgeClient {

    private final SyncServiceClient client;

    public CmsBridgeClient(
            RestClient.Builder builder, @Value("${app.publishing-service-uri}") String publishingServiceUri) {
        this.client = SyncServiceClient.builder(builder, "publishing-service", publishingServiceUri)
                .profile(SyncCallProfile.RENDER) // 大きめのメディアファイル転送を伴うため、単純なJSON APIより長めの30秒
                .build();
    }

    public MediaUploadResult uploadMedia(
            String siteKey, String filename, String contentType, byte[] data, String bearerToken) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        ByteArrayResource resource = new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        body.part("file", resource)
                .header(HttpHeaders.CONTENT_TYPE, contentType != null ? contentType : "application/octet-stream");
        try {
            MediaUploadResult result = client.postMultipart(
                    "/api/internal/publishing/sites/{site}/media", new Object[] {siteKey}, body.build(),
                    MediaUploadResult.class, ServiceAuthHeaders.forwardedBearer(bearerToken));
            if (result == null) {
                throw new CmsBridgeException("publishing-serviceから空の応答を受け取りました", null);
            }
            return result;
        } catch (SyncServiceException e) {
            throw new CmsBridgeException("publishing-serviceのメディアアップロード呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    public MediaGcScanResult scanMedia(Long projectId, String environment, String bearerToken) {
        try {
            MediaGcScanResult result = client.get(
                    "/api/internal/publishing/projects/{projectId}/media-scan?environment={environment}",
                    new Object[] {projectId, environment}, MediaGcScanResult.class,
                    ServiceAuthHeaders.forwardedBearer(bearerToken));
            if (result == null) {
                throw new CmsBridgeException("publishing-serviceから空の応答を受け取りました", null);
            }
            return result;
        } catch (SyncServiceException e) {
            throw new CmsBridgeException("publishing-serviceのメディアスキャン呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    public void deleteMedia(Long projectId, String environment, String mediaId, String bearerToken) {
        try {
            client.delete(
                    "/api/internal/publishing/projects/{projectId}/media/{mediaId}?environment={environment}",
                    new Object[] {projectId, mediaId, environment}, ServiceAuthHeaders.forwardedBearer(bearerToken));
        } catch (SyncServiceException e) {
            throw new CmsBridgeException("publishing-serviceのメディア削除呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }
}
