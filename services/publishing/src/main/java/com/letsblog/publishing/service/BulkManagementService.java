package com.letsblog.publishing.service;

import com.letsblog.publishing.client.MediaSettingsBridgeClient;
import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.MediaUploadResult;
import com.letsblog.publishing.cms.ssh.WordPressSshOperations;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationSourceType;
import com.letsblog.publishing.domain.BulkOperationStatus;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.domain.Site;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient.BulkApplyCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * カテゴリ/タグ/プラグイン/テーマの操作を単一環境へ適用する({@link #applyToEnvironment})、
 * またはzipアップロードによるプラグイン/テーマのインストールを全managed環境へ一括実行する
 * ({@link #executeFromUpload})。実行結果は非永続のBulkOperationLog値オブジェクトとして
 * 呼び出し元(画面)へ返すのみで、履歴の永続化・一覧・ロールフォワードは行わない
 * (issue #184。以前はbulk_operation_logsへ保存していたが、操作ログ(operation_logs)へ一本化した)。
 * 環境をまたぐ比較・同期(マスター環境の値を他環境へ反映する等)のオーケストレーションは
 * {@link TermComparisonService}が本クラスの{@link #applyToEnvironment}を都度呼び出す形で行う。
 *
 * <p>legacy-apiの{@code com.letsblog.api.service.BulkManagementService}をpublishing-serviceへ
 * 移設したもの(issue #708、Epic #551 C6-2)。プロジェクト単位の投稿画像リサイズ設定
 * (project_image_settings、legacy-api所有)の解決は{@link MediaSettingsBridgeClient}経由の内部ブリッジに
 * 置き換えた({@code ProjectService#resolveArticleImageLongEdgePx}を直接呼んでいた旧実装から変更)。
 */
@Service
@Slf4j
public class BulkManagementService {

    private static final List<String> ENVIRONMENT_ORDER = List.of("local", "test", "production");
    private static final Set<String> VALID_ENVIRONMENTS = Set.of("local", "test", "production");

    private final WordPressBulkManagementClient bulkManagementClient;
    private final BulkUploadStorageService bulkUploadStorageService;
    private final SiteService siteService;
    private final WordPressSshOperations sshOperations;
    private final CmsAdapterFactory cmsAdapterFactory;
    private final ProjectService projectService;
    private final ImageResizeService imageResizeService;
    private final MediaSettingsBridgeClient mediaSettingsBridgeClient;

    public BulkManagementService(
            WordPressBulkManagementClient bulkManagementClient,
            BulkUploadStorageService bulkUploadStorageService,
            SiteService siteService,
            WordPressSshOperations sshOperations,
            CmsAdapterFactory cmsAdapterFactory,
            ProjectService projectService,
            ImageResizeService imageResizeService,
            MediaSettingsBridgeClient mediaSettingsBridgeClient) {
        this.bulkManagementClient = bulkManagementClient;
        this.bulkUploadStorageService = bulkUploadStorageService;
        this.siteService = siteService;
        this.sshOperations = sshOperations;
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.projectService = projectService;
        this.imageResizeService = imageResizeService;
        this.mediaSettingsBridgeClient = mediaSettingsBridgeClient;
    }

    /**
     * 単一環境に対して1件の操作を適用し、1件のBulkOperationLogとして結果を返す。
     * カテゴリ/タグの比較テーブル(新規追加・編集はマスター環境のみ、削除・同期は非マスター環境も含む)・
     * プラグイン/テーマの状態反映(環境ごとのセル単位)のいずれからも呼ばれる共通経路。
     */
    public BulkOperationLog applyToEnvironment(
            Long projectId, String environment, BulkOperationType type, String value,
            String categorySlug, String categoryParentSlug, String categoryDescription,
            String categoryTargetSlug, Long actorId) {
        String effectiveValue = requireFields(type, value, categorySlug, categoryTargetSlug);
        Project project = getProject(projectId);
        Site site = resolveSite(project, environment);
        return applyToSite(projectId, environment, site, type, effectiveValue,
                categorySlug, categoryParentSlug, categoryDescription, categoryTargetSlug, actorId);
    }

    /**
     * slugベースのプラグイン/テーマインストールを、紐付いている全環境へ一括実行する(issue #393。
     * 従来は{@link #applyToEnvironment}で環境を1つずつ選んで実行する必要があった)。
     * {@link #executeFromUpload}と同様、1環境の失敗(SSH未設定等)が他環境の実行を止めないよう、
     * resolveSite/applyToSiteの失敗はFAILEDのBulkOperationLogとして記録し次の環境へ進む。
     */
    public List<BulkOperationLog> applyToAllEnvironments(
            Long projectId, BulkOperationType type, String value, Long actorId) {
        if (!type.supportsZipUpload()) {
            throw new IllegalArgumentException("全環境への一括インストールはプラグイン/テーマのインストールのみ対応しています");
        }
        requireNonBlank(value, "slugを入力してください");
        Project project = getProject(projectId);
        List<BulkOperationLog> results = new ArrayList<>();
        for (String environment : ENVIRONMENT_ORDER) {
            if (siteIdOf(project, environment) == null) {
                continue;
            }
            try {
                Site site = resolveSite(project, environment);
                results.add(applyToSite(projectId, environment, site, type, value,
                        null, null, null, null, actorId));
            } catch (IllegalArgumentException e) {
                results.add(saveLog(projectId, type, BulkOperationSourceType.SLUG, value,
                        null, null, null, null, null, null, null,
                        environment, BulkOperationStatus.FAILED.name(), e.getMessage(), null, actorId));
            }
        }
        return results;
    }

    public List<BulkOperationLog> executeFromUpload(
            Long projectId, BulkOperationType type, MultipartFile file, Long actorId) throws IOException {
        if (!type.supportsZipUpload()) {
            throw new IllegalArgumentException("zipアップロードはプラグイン/テーマのインストールのみ対応しています");
        }
        validateZip(file);
        Project project = getProject(projectId);
        List<Map.Entry<String, Site>> environments = resolveZipUploadEnvironments(project);

        byte[] bytes = file.getBytes();
        BulkUploadStorageService.StoredZip stored =
                bulkUploadStorageService.store(projectId, bytes, file.getOriginalFilename());

        List<BulkOperationLog> results = new ArrayList<>();
        for (Map.Entry<String, Site> entry : environments) {
            ZipApplyResult result = applyZipToSite(entry.getValue(), type, bytes, stored.originalFilename());
            results.add(saveLog(projectId, type, BulkOperationSourceType.ZIP, stored.originalFilename(),
                    null, null, null, null,
                    stored.originalFilename(), stored.storagePath(), stored.sha256(),
                    entry.getKey(), result.status(), result.errorMessage(), result.stackTrace(), actorId));
        }
        return results;
    }

    /**
     * zipアップロードは、managed環境(内部プロビジョニングエージェント経由)に加え、
     * SSH接続情報が設定された非managed環境も対象にする。
     */
    private List<Map.Entry<String, Site>> resolveZipUploadEnvironments(Project project) {
        List<Map.Entry<String, Site>> result = new ArrayList<>();
        for (String environment : ENVIRONMENT_ORDER) {
            Long siteId = siteIdOf(project, environment);
            if (siteId == null) {
                continue;
            }
            Site site = siteService.getById(siteId).orElse(null);
            if (site == null) {
                continue;
            }
            if (site.isManagedWordpress() || siteService.resolveDataSource(site).hasSsh()) {
                result.add(new AbstractMap.SimpleEntry<>(environment, site));
            }
        }
        return result;
    }

    private ZipApplyResult applyZipToSite(Site site, BulkOperationType type, byte[] bytes, String filename) {
        if (site.isManagedWordpress()) {
            WordPressBulkManagementClient.BulkApplyResult result =
                    bulkManagementClient.applyZip(site.getWpSlug(), type.wpCliAction(), bytes, filename);
            return new ZipApplyResult(result.status(), result.errorMessage(), result.stackTrace());
        }
        SiteService.SiteDataSource dataSource = siteService.resolveDataSource(site);
        WordPressSshOperations.SshApplyResult result = sshOperations.applyZip(dataSource.sshCredentials(), type, bytes, filename);
        return new ZipApplyResult(result.status(), result.errorMessage(), result.stackTrace());
    }

    private record ZipApplyResult(String status, String errorMessage, String stackTrace) {
    }

    /**
     * 生成画像をプロジェクトのlocal/test/production環境(サイトが紐付けられているもののみ)へ
     * アセットとしてアップロードする。CmsAdapter.uploadMediaは認証情報(managed/SSH)に応じた
     * トランスポート選択を内部で行うため、applyToSite()のような分岐は不要でサイトごとに委譲するだけでよい。
     * 環境単位で成否をBulkOperationLogへ記録する(1環境の失敗が他環境の実行を止めない)。
     * アップロード前に、記事本文画像と同じ長編px基準(MediaSettingsBridgeClient#resolveArticleImageLongEdgePx)で
     * リサイズする(issue #440。生成画像はデフォルト1920x1080等でリサイズされずにそのままアップロード
     * されていた)。あわせて、透過を持たないPNGはJPEGへ変換してファイルサイズを削減する
     * (issue #468。ComfyUI生成画像はPNGのため容量が大きい)。全環境で同じ結果を使い回すため、
     * 環境ループの前に1回だけ行う。
     */
    public List<BulkOperationLog> uploadImageToAllEnvironments(
            Long projectId, byte[] data, String filename, String contentType, Long actorId) {
        Project project = getProject(projectId);
        int longEdgePx = mediaSettingsBridgeClient.resolveArticleImageLongEdgePx(projectId);
        ImageResizeService.ResizeResult resized =
                imageResizeService.resizeToLongEdge(data, contentType, longEdgePx, true);
        byte[] resizedData = resized.data();
        String resizedContentType = resized.mimeType();
        String resizedFilename = withExtensionFor(filename, resizedContentType);
        List<BulkOperationLog> results = new ArrayList<>();
        for (String environment : ENVIRONMENT_ORDER) {
            Long siteId = siteIdOf(project, environment);
            if (siteId == null) {
                continue;
            }
            Site site = siteService.getById(siteId).orElse(null);
            if (site == null) {
                continue;
            }

            String status;
            String errorMessage = null;
            String value;
            try {
                CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
                CmsAdapter adapter = cmsAdapterFactory.resolve(credentials.cmsType());
                MediaUploadResult result =
                        adapter.uploadMedia(credentials, resizedFilename, resizedContentType, resizedData);
                status = BulkOperationStatus.SUCCESS.name();
                value = result.url();
            } catch (RuntimeException e) {
                status = BulkOperationStatus.FAILED.name();
                errorMessage = e.getMessage();
                value = resizedFilename;
                log.warn("アセット画像のアップロードに失敗しました(project={}, environment={}, site={}): {}",
                        projectId, environment, site.getSiteKey(), errorMessage);
            }
            results.add(saveLog(projectId, BulkOperationType.MEDIA_UPLOAD, BulkOperationSourceType.SLUG, value,
                    null, null, null, null, resizedFilename, null, null,
                    environment, status, errorMessage, null, actorId));
        }
        return results;
    }

    /** ファイル名の拡張子をmimeTypeに合わせて置き換える(JPEG変換時に.pngのまま送信しないため)。 */
    private String withExtensionFor(String filename, String mimeType) {
        String extension = "image/jpeg".equalsIgnoreCase(mimeType) ? ".jpg"
                : "image/png".equalsIgnoreCase(mimeType) ? ".png"
                : null;
        if (extension == null) {
            return filename;
        }
        int lastDot = filename.lastIndexOf('.');
        String base = lastDot >= 0 ? filename.substring(0, lastDot) : filename;
        return base + extension;
    }

    /**
     * 指定環境の1投稿/ページを削除する(PostComparisonService#deleteEverywhereが環境ごとに呼ぶ)。
     * カテゴリ/タグ/プラグイン/テーマのようなwp-cliアクション文字列を経由せず、CmsAdapterへ直接委譲する
     * (投稿一覧取得(listPosts)と同じCmsAdapter経由でmanaged/SSHが解決される)。
     */
    public BulkOperationLog deletePostAtEnvironment(
            Long projectId, String environment, Site site, String postId, String postType, String slug, Long actorId) {
        String status;
        String errorMessage = null;
        try {
            CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
            CmsAdapter adapter = cmsAdapterFactory.resolve(credentials.cmsType());
            adapter.deletePost(credentials, postId, postType);
            status = BulkOperationStatus.SUCCESS.name();
        } catch (RuntimeException e) {
            status = BulkOperationStatus.FAILED.name();
            errorMessage = e.getMessage();
            log.warn("投稿/ページの削除に失敗しました(project={}, environment={}, site={}, postId={}): {}",
                    projectId, environment, site.getSiteKey(), postId, errorMessage);
        }
        return saveLog(projectId, BulkOperationType.POST_DELETE, BulkOperationSourceType.SLUG, slug,
                null, null, null, null, null, null, null, environment, status, errorMessage, null, actorId);
    }

    /**
     * 指定環境の1投稿/ページのステータスを変更する(PostComparisonService#updateStatusEverywhereが環境ごとに呼ぶ)。
     */
    public BulkOperationLog updatePostStatusAtEnvironment(
            Long projectId, String environment, Site site, String postId, String postType, String slug,
            String newStatus, Long actorId) {
        String status;
        String errorMessage = null;
        try {
            CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
            CmsAdapter adapter = cmsAdapterFactory.resolve(credentials.cmsType());
            adapter.updatePostStatus(credentials, postId, postType, newStatus);
            status = BulkOperationStatus.SUCCESS.name();
        } catch (RuntimeException e) {
            status = BulkOperationStatus.FAILED.name();
            errorMessage = e.getMessage();
            log.warn("投稿/ページのステータス変更に失敗しました(project={}, environment={}, site={}, postId={}): {}",
                    projectId, environment, site.getSiteKey(), postId, errorMessage);
        }
        return saveLog(projectId, BulkOperationType.POST_STATUS_UPDATE, BulkOperationSourceType.SLUG, slug,
                null, null, null, null, null, null, null, newStatus, environment, status, errorMessage, null,
                actorId);
    }

    private BulkOperationLog applyToSite(
            Long projectId, String environment, Site site, BulkOperationType type, String value,
            String categorySlug, String categoryParentSlug, String categoryDescription, String categoryTargetSlug,
            Long actorId) {
        String status;
        String errorMessage;
        String stackTrace;
        String transportLabel;
        if (site.isManagedWordpress()) {
            WordPressBulkManagementClient.BulkApplyResult result = bulkManagementClient.apply(new BulkApplyCommand(
                    site.getWpSlug(), type.wpCliAction(), value,
                    categorySlug, categoryParentSlug, categoryDescription, categoryTargetSlug));
            status = result.status();
            errorMessage = result.errorMessage();
            stackTrace = result.stackTrace();
            transportLabel = "内部エージェント";
        } else {
            // resolveSite()がSSH経由適用可能と判定した(managedでない)サイトはここに到達する。
            SiteService.SiteDataSource dataSource = siteService.resolveDataSource(site);
            if (!dataSource.hasSsh()) {
                // resolveSite()が事前に検証しているため通常到達しない
                throw new IllegalArgumentException(
                        environment + "環境(" + site.getSiteKey() + ")はSSH接続が設定されていないため操作できません");
            }
            WordPressSshOperations.SshApplyResult result = isCategoryOrTag(type)
                    ? sshOperations.applyTerm(dataSource.sshCredentials(), type, value, categorySlug,
                            categoryParentSlug, categoryDescription, categoryTargetSlug)
                    : sshOperations.applyPluginTheme(dataSource.sshCredentials(), type, value);
            transportLabel = "SSH";
            status = result.status();
            errorMessage = result.errorMessage();
            stackTrace = result.stackTrace();
            if (BulkOperationStatus.FAILED.name().equals(status)) {
                log.warn("{}経由の操作に失敗しました(project={}, environment={}, site={}, type={}): {}",
                        transportLabel, projectId, environment, site.getSiteKey(), type, errorMessage);
            }
        }
        return saveLog(projectId, type, BulkOperationSourceType.SLUG, value,
                categorySlug, categoryParentSlug, categoryTargetSlug, categoryDescription, null, null, null,
                environment, status, errorMessage, stackTrace, actorId);
    }

    /**
     * カテゴリ/タグ/プラグイン/テーマの一覧取得(読み取り)が失敗した際に、アプリケーションログへ
     * 警告として記録するだけの軽量な経路。resolveSite/applyToSiteのような解決・適用処理は行わない
     * (TermComparisonService/PluginThemeComparisonServiceがSSH取得の失敗を検知した時点で呼ぶ)。
     */
    public void logFetchFailure(
            Long projectId, BulkOperationType type, String environment, String errorMessage, String stackTrace) {
        log.warn("一覧取得に失敗しました(project={}, type={}, environment={}): {}\n{}",
                projectId, type, environment, errorMessage, stackTrace);
    }

    /**
     * operationTypeごとに必須項目を検証し、ログ・wp-cli呼び出しに使う実効値(value)を返す。
     * CATEGORY_DELETE/TAG_DELETEはUI上valueの入力が不要なため、表示用にcategoryTargetSlugを補う。
     */
    private String requireFields(BulkOperationType type, String value, String categorySlug, String categoryTargetSlug) {
        switch (type) {
            case CATEGORY_CREATE, TAG_CREATE -> {
                requireNonBlank(value, "名前を入力してください");
                requireNonBlank(categorySlug, "スラッグを入力してください");
                return value;
            }
            case CATEGORY_EDIT, TAG_EDIT -> {
                requireNonBlank(categoryTargetSlug, "編集対象を選択してください");
                requireNonBlank(value, "名前を入力してください");
                requireNonBlank(categorySlug, "スラッグを入力してください");
                return value;
            }
            case CATEGORY_DELETE, TAG_DELETE -> {
                requireNonBlank(categoryTargetSlug, "削除対象を選択してください");
                return (value == null || value.isBlank()) ? categoryTargetSlug : value;
            }
            default -> {
                requireNonBlank(value, "slugを入力してください");
                return value;
            }
        }
    }

    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private BulkOperationLog saveLog(
            Long projectId, BulkOperationType type, BulkOperationSourceType sourceType, String value,
            String categorySlug, String categoryParentSlug, String categoryTargetSlug, String categoryDescription,
            String originalFilename, String storagePath, String fileSha256, String environment,
            String status, String errorMessage, String stackTrace, Long actorId) {
        return saveLog(projectId, type, sourceType, value, categorySlug, categoryParentSlug, categoryTargetSlug,
                categoryDescription, originalFilename, storagePath, fileSha256, null, environment, status,
                errorMessage, stackTrace, actorId);
    }

    /** 実行結果を非永続のBulkOperationLog値オブジェクトとして組み立てて返す(issue #184)。 */
    private BulkOperationLog saveLog(
            Long projectId, BulkOperationType type, BulkOperationSourceType sourceType, String value,
            String categorySlug, String categoryParentSlug, String categoryTargetSlug, String categoryDescription,
            String originalFilename, String storagePath, String fileSha256, String postStatus, String environment,
            String status, String errorMessage, String stackTrace, Long actorId) {
        BulkOperationLog log = new BulkOperationLog();
        log.setProjectId(projectId);
        log.setOperationType(type);
        log.setSourceType(sourceType);
        log.setValue(value);
        log.setCategorySlug(categorySlug);
        log.setCategoryParentSlug(categoryParentSlug);
        log.setCategoryTargetSlug(categoryTargetSlug);
        log.setCategoryDescription(categoryDescription);
        log.setOriginalFilename(originalFilename);
        log.setStoragePath(storagePath);
        log.setFileSha256(fileSha256);
        log.setPostStatus(postStatus);
        log.setEnvironment(environment);
        BulkOperationStatus resolvedStatus = BulkOperationStatus.valueOf(status);
        log.setStatus(resolvedStatus);
        log.setLevel(resolvedStatus.toLogLevel());
        log.setErrorMessage(errorMessage);
        log.setStackTrace(stackTrace);
        log.setActorId(actorId);
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }

    /**
     * environmentの形式検証とサイト紐付けの存在確認のみを行う(managed/SSH等のポリシー判定は行わない)。
     */
    private Site requireSiteBound(Project project, String environment) {
        if (!VALID_ENVIRONMENTS.contains(environment)) {
            throw new IllegalArgumentException("environment は local/test/production のいずれかを指定してください");
        }
        Long siteId = siteIdOf(project, environment);
        if (siteId == null) {
            throw new IllegalArgumentException(environment + "環境にはサイトが紐付けられていません");
        }
        return siteService.getById(siteId)
                .orElseThrow(() -> new SiteNotFoundException("id " + siteId + " のサイトは登録されていません"));
    }

    /**
     * managedサイトに加え、非managedサイトでもSSHが設定されていれば許可する(適用は{@link #applyToSite}が担う)。
     */
    private Site resolveSite(Project project, String environment) {
        Site site = requireSiteBound(project, environment);
        if (site.isManagedWordpress()) {
            return site;
        }
        SiteService.SiteDataSource dataSource = siteService.resolveDataSource(site);
        if (dataSource.hasSsh()) {
            return site;
        }
        throw new IllegalArgumentException(
                environment + "環境(" + site.getSiteKey() + ")は自動構築サイトでもSSH接続設定済みサイトでもないため対象外です");
    }

    private boolean isCategoryOrTag(BulkOperationType type) {
        return switch (type) {
            case CATEGORY_CREATE, CATEGORY_EDIT, CATEGORY_DELETE, TAG_CREATE, TAG_EDIT, TAG_DELETE -> true;
            default -> false;
        };
    }

    private Long siteIdOf(Project project, String environment) {
        return switch (environment) {
            case "local" -> project.getLocalSiteId();
            case "test" -> project.getTestSiteId();
            case "production" -> project.getProductionSiteId();
            default -> null;
        };
    }

    private void validateZip(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("アップロードするzipファイルを指定してください");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("zipファイル(.zip)を指定してください");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.contains("zip") && !contentType.equals("application/octet-stream")) {
            throw new IllegalArgumentException("zipファイル(.zip)を指定してください");
        }
    }

    private Project getProject(Long projectId) {
        return projectService.getProjectEntity(projectId);
    }
}
