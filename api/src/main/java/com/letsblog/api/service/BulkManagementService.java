package com.letsblog.api.service;

import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.cms.rest.WordPressRestBulkManagementOperations;
import com.letsblog.api.cms.ssh.WordPressSshOperations;
import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationLogLevel;
import com.letsblog.api.domain.BulkOperationSourceType;
import com.letsblog.api.domain.BulkOperationStatus;
import com.letsblog.api.domain.BulkOperationType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.provisioning.WordPressBulkManagementClient;
import com.letsblog.api.provisioning.WordPressBulkManagementClient.BulkApplyCommand;
import com.letsblog.api.repository.BulkOperationLogRepository;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
import com.letsblog.api.util.StackTraceUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * カテゴリ/タグ/プラグイン/テーマの操作を単一環境へ適用する({@link #applyToEnvironment})、
 * またはzipアップロードによるプラグイン/テーマのインストールを全managed環境へ一括実行する
 * ({@link #executeFromUpload})。実行内容はbulk_operation_logsへ保存し、任意の1環境への
 * ロールフォワード(過去の成功ログの再適用、{@link #replay})に使う。
 * 環境をまたぐ比較・同期(マスター環境の値を他環境へ反映する等)のオーケストレーションは
 * {@link TermComparisonService}が本クラスの{@link #applyToEnvironment}を都度呼び出す形で行う。
 */
@Service
@Slf4j
public class BulkManagementService {

    private static final List<String> ENVIRONMENT_ORDER = List.of("local", "test", "production");
    private static final Set<String> VALID_ENVIRONMENTS = Set.of("local", "test", "production");

    private final ProjectRepository projectRepository;
    private final SiteRepository siteRepository;
    private final BulkOperationLogRepository bulkOperationLogRepository;
    private final WordPressBulkManagementClient bulkManagementClient;
    private final BulkUploadStorageService bulkUploadStorageService;
    private final SiteService siteService;
    private final WordPressSshOperations sshOperations;
    private final WordPressRestBulkManagementOperations restOperations;
    private final CmsAdapterFactory cmsAdapterFactory;

    public BulkManagementService(
            ProjectRepository projectRepository,
            SiteRepository siteRepository,
            BulkOperationLogRepository bulkOperationLogRepository,
            WordPressBulkManagementClient bulkManagementClient,
            BulkUploadStorageService bulkUploadStorageService,
            SiteService siteService,
            WordPressSshOperations sshOperations,
            WordPressRestBulkManagementOperations restOperations,
            CmsAdapterFactory cmsAdapterFactory) {
        this.projectRepository = projectRepository;
        this.siteRepository = siteRepository;
        this.bulkOperationLogRepository = bulkOperationLogRepository;
        this.bulkManagementClient = bulkManagementClient;
        this.bulkUploadStorageService = bulkUploadStorageService;
        this.siteService = siteService;
        this.sshOperations = sshOperations;
        this.restOperations = restOperations;
        this.cmsAdapterFactory = cmsAdapterFactory;
    }

    /**
     * 単一環境に対して1件の操作を適用し、1件のBulkOperationLogとして記録する。
     * カテゴリ/タグの比較テーブル(新規追加・編集はマスター環境のみ、削除・同期は非マスター環境も含む)・
     * プラグイン/テーマの状態反映(環境ごとのセル単位)のいずれからも呼ばれる共通経路。
     */
    @Transactional
    public BulkOperationLog applyToEnvironment(
            Long projectId, String environment, BulkOperationType type, String value,
            String categorySlug, String categoryParentSlug, String categoryDescription,
            String categoryTargetSlug, Long actorId) {
        String effectiveValue = requireFields(type, value, categorySlug, categoryTargetSlug);
        Project project = getProject(projectId);
        Site site = resolveSite(project, environment, type);
        return applyToSite(projectId, environment, site, type, effectiveValue,
                categorySlug, categoryParentSlug, categoryDescription, categoryTargetSlug, actorId, false);
    }

    @Transactional
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
                    entry.getKey(), result.status(), result.errorMessage(), result.stackTrace(), actorId, false));
        }
        return results;
    }

    /**
     * zipアップロードは、managed環境(内部プロビジョニングエージェント経由)に加え、
     * SSH接続情報が設定された非managed環境も対象にする(REST APIにはzipインストールに
     * 相当するエンドポイントが無いため、非managedはSSHが設定されている場合のみ対応)。
     */
    private List<Map.Entry<String, Site>> resolveZipUploadEnvironments(Project project) {
        List<Map.Entry<String, Site>> result = new ArrayList<>();
        for (String environment : ENVIRONMENT_ORDER) {
            Long siteId = siteIdOf(project, environment);
            if (siteId == null) {
                continue;
            }
            Site site = siteRepository.findById(siteId).orElse(null);
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
     * アセットとしてアップロードする。CmsAdapter.uploadMediaは認証情報(managed/SSH/REST)に応じた
     * トランスポート選択を内部で行うため、applyToSite()のような分岐は不要でサイトごとに委譲するだけでよい。
     * 環境単位で成否をBulkOperationLogへ記録する(1環境の失敗が他環境の実行を止めない)。
     */
    @Transactional
    public List<BulkOperationLog> uploadImageToAllEnvironments(
            Long projectId, byte[] data, String filename, String contentType, Long actorId) {
        Project project = getProject(projectId);
        List<BulkOperationLog> results = new ArrayList<>();
        for (String environment : ENVIRONMENT_ORDER) {
            Long siteId = siteIdOf(project, environment);
            if (siteId == null) {
                continue;
            }
            Site site = siteRepository.findById(siteId).orElse(null);
            if (site == null) {
                continue;
            }

            String status;
            String errorMessage = null;
            String value;
            try {
                CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
                CmsAdapter adapter = cmsAdapterFactory.resolve(credentials.cmsType());
                MediaUploadResult result = adapter.uploadMedia(credentials, filename, contentType, data);
                status = BulkOperationStatus.SUCCESS.name();
                value = result.url();
            } catch (RuntimeException e) {
                status = BulkOperationStatus.FAILED.name();
                errorMessage = e.getMessage();
                value = filename;
                log.warn("アセット画像のアップロードに失敗しました(project={}, environment={}, site={}): {}",
                        projectId, environment, site.getSiteKey(), errorMessage);
            }
            results.add(saveLog(projectId, BulkOperationType.MEDIA_UPLOAD, BulkOperationSourceType.SLUG, value,
                    null, null, null, null, filename, null, null,
                    environment, status, errorMessage, null, actorId, false));
        }
        return results;
    }

    /**
     * 指定環境の1投稿/ページを削除する(PostComparisonService#deleteEverywhereが環境ごとに呼ぶ)。
     * カテゴリ/タグ/プラグイン/テーマのようなwp-cliアクション文字列を経由せず、CmsAdapterへ直接委譲する
     * (投稿一覧取得(listPosts)と同じCmsAdapter経由でmanaged/SSH/RESTが解決される)。
     */
    @Transactional
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
                null, null, null, null, null, null, null, environment, status, errorMessage, null, actorId, false);
    }

    /**
     * 指定環境の1投稿/ページのステータスを変更する(PostComparisonService#updateStatusEverywhereが環境ごとに呼ぶ)。
     */
    @Transactional
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
                actorId, false);
    }

    @Transactional
    public List<BulkOperationLog> replay(Long projectId, String environment, Long actorId) {
        Project project = getProject(projectId);
        // 環境が不正・未紐付けの場合は、履歴の有無に関わらず即座に例外にする(fail-fast)
        requireSiteBound(project, environment);

        List<BulkOperationLog> history = bulkOperationLogRepository
                .findByProjectIdAndStatusOrderByCreatedAtAsc(projectId, BulkOperationStatus.SUCCESS);

        List<BulkOperationLog> results = new ArrayList<>();
        for (BulkOperationLog log : history) {
            if (!log.getOperationType().isReplayable()) {
                continue;
            }
            if (log.getSourceType() == BulkOperationSourceType.ZIP) {
                // zipアップロードの再現は、実行時(executeFromUpload)と同じくmanaged環境に加え
                // SSH接続情報が設定された非managed環境も対象にする
                Site targetSite = resolveZipCapableSite(project, environment);
                results.add(replayZip(projectId, environment, targetSite, log, actorId));
            } else {
                Site targetSite = resolveSite(project, environment, log.getOperationType());
                results.add(applyToSite(projectId, environment, targetSite, log.getOperationType(), log.getValue(),
                        log.getCategorySlug(), log.getCategoryParentSlug(), log.getCategoryDescription(),
                        log.getCategoryTargetSlug(), actorId, true));
            }
        }
        return results;
    }

    @Transactional(readOnly = true)
    public List<BulkOperationLog> listLogs(Long projectId) {
        return bulkOperationLogRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public List<BulkOperationLog> listLogs(
            Long projectId, BulkOperationType operationType, String environment, BulkOperationLogLevel level) {
        return bulkOperationLogRepository.findByFilters(projectId, operationType, environment, level);
    }

    @Transactional
    public void clearLogs(Long projectId) {
        bulkOperationLogRepository.deleteByProjectId(projectId);
    }

    private BulkOperationLog applyToSite(
            Long projectId, String environment, Site site, BulkOperationType type, String value,
            String categorySlug, String categoryParentSlug, String categoryDescription, String categoryTargetSlug,
            Long actorId, boolean isReplay) {
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
            // resolveSite()がREST/SSH経由適用可能と判定した(managedでない)サイトはここに到達する。
            // SSHが設定されていれば優先して使う(REST APIはロール権限不足等で拒否されるケースがあるため)。
            // SSHでの実行が失敗した場合、テーマの書き込み以外はRESTが設定されていればフォールバックする
            // (テーマの作成/有効化/削除はコアのREST APIに書き込みエンドポイントが無いため対象外)。
            // SSHが設定されていなければRESTのみで実行する。
            SiteService.SiteDataSource dataSource = siteService.resolveDataSource(site);
            WordPressSshOperations.SshApplyResult result;
            if (dataSource.hasSsh()) {
                result = isCategoryOrTag(type)
                        ? sshOperations.applyTerm(dataSource.sshCredentials(), type, value, categorySlug,
                                categoryParentSlug, categoryDescription, categoryTargetSlug)
                        : sshOperations.applyPluginTheme(dataSource.sshCredentials(), type, value);
                transportLabel = "SSH";
                if (BulkOperationStatus.FAILED.name().equals(result.status())
                        && !isThemeWrite(type) && dataSource.hasRest()) {
                    log.warn("SSH経由の操作に失敗したためREST APIにフォールバックします"
                                    + "(project={}, environment={}, site={}, type={}): {}",
                            projectId, environment, site.getSiteKey(), type, result.errorMessage());
                    result = isCategoryOrTag(type)
                            ? restOperations.applyTerm(dataSource.restCredentials(), type, value, categorySlug,
                                    categoryParentSlug, categoryDescription, categoryTargetSlug)
                            : restOperations.applyPlugin(dataSource.restCredentials(), type, value);
                    transportLabel = "REST API(SSH失敗によるフォールバック)";
                }
            } else if (!isThemeWrite(type) && dataSource.hasRest()) {
                result = isCategoryOrTag(type)
                        ? restOperations.applyTerm(dataSource.restCredentials(), type, value, categorySlug,
                                categoryParentSlug, categoryDescription, categoryTargetSlug)
                        : restOperations.applyPlugin(dataSource.restCredentials(), type, value);
                transportLabel = "REST API";
            } else {
                // resolveSite()が事前に検証しているため通常到達しない
                throw new IllegalArgumentException(
                        environment + "環境(" + site.getSiteKey() + ")はREST/SSHのいずれでも操作できません");
            }
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
                environment, status, errorMessage, stackTrace, actorId, isReplay);
    }

    /**
     * カテゴリ/タグ/プラグイン/テーマの一覧取得(読み取り)が失敗した際に、作業ログへ記録するだけの
     * 軽量な経路。resolveSite/applyToSiteのような解決・適用処理は行わない(TermComparisonService/
     * PluginThemeComparisonServiceがREST/SSH取得の失敗を検知した時点で呼ぶ)。
     * 呼び出し元(listCategoryComparison等)は@Transactional(readOnly = true)なので、
     * 同じトランザクションに相乗りすると書き込みができずSQL例外になる。REQUIRES_NEWで
     * 独立したトランザクションとして必ずコミットする。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFetchFailure(
            Long projectId, BulkOperationType type, String environment, String errorMessage, String stackTrace) {
        saveLog(projectId, type, BulkOperationSourceType.SLUG, "-", null, null, null, null, null, null, null,
                environment, BulkOperationStatus.FAILED.name(), errorMessage, stackTrace, null, false);
    }

    private BulkOperationLog replayZip(
            Long projectId, String environment, Site targetSite, BulkOperationLog log, Long actorId) {
        String status;
        String errorMessage;
        String stackTrace;
        try {
            byte[] bytes = bulkUploadStorageService.load(log.getStoragePath());
            ZipApplyResult result = applyZipToSite(targetSite, log.getOperationType(), bytes, log.getOriginalFilename());
            status = result.status();
            errorMessage = result.errorMessage();
            stackTrace = result.stackTrace();
        } catch (IOException e) {
            status = BulkOperationStatus.FAILED.name();
            errorMessage = "元ファイルが見つかりません。再度アップロードしてください: " + e.getMessage();
            stackTrace = StackTraceUtil.toString(e);
        }
        return saveLog(projectId, log.getOperationType(), log.getSourceType(), log.getValue(),
                log.getCategorySlug(), log.getCategoryParentSlug(), log.getCategoryTargetSlug(),
                log.getCategoryDescription(),
                log.getOriginalFilename(), log.getStoragePath(), log.getFileSha256(),
                environment, status, errorMessage, stackTrace, actorId, true);
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
            String status, String errorMessage, String stackTrace, Long actorId, boolean isReplay) {
        return saveLog(projectId, type, sourceType, value, categorySlug, categoryParentSlug, categoryTargetSlug,
                categoryDescription, originalFilename, storagePath, fileSha256, null, environment, status,
                errorMessage, stackTrace, actorId, isReplay);
    }

    private BulkOperationLog saveLog(
            Long projectId, BulkOperationType type, BulkOperationSourceType sourceType, String value,
            String categorySlug, String categoryParentSlug, String categoryTargetSlug, String categoryDescription,
            String originalFilename, String storagePath, String fileSha256, String postStatus, String environment,
            String status, String errorMessage, String stackTrace, Long actorId, boolean isReplay) {
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
        log.setReplay(isReplay);
        log.setActorId(actorId);
        return bulkOperationLogRepository.save(log);
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
        return siteRepository.findById(siteId)
                .orElseThrow(() -> new SiteNotFoundException("id " + siteId + " のサイトは登録されていません"));
    }

    /**
     * zipアップロード(実行/再現)が対象にできるサイトかを検証する。managed環境に加え、
     * SSH接続情報が設定された非managed環境も許可する(REST APIにはzipインストールに
     * 相当するエンドポイントが無いため対象外)。
     */
    private Site resolveZipCapableSite(Project project, String environment) {
        Site site = requireSiteBound(project, environment);
        if (site.isManagedWordpress() || siteService.resolveDataSource(site).hasSsh()) {
            return site;
        }
        throw new IllegalArgumentException(
                environment + "環境(" + site.getSiteKey() + ")はzipアップロードに対応するSSH接続設定がないため対象外です");
    }

    /**
     * managedサイトに加え、非managedサイトでもREST(Application Password)またはSSHが設定されていれば
     * 許可する(適用は{@link #applyToSite}が担う)。ただしテーマの作成/有効化/削除はコアのREST APIに
     * 書き込みエンドポイントが無いため、SSHが設定されている場合のみ許可する。
     */
    private Site resolveSite(Project project, String environment, BulkOperationType type) {
        Site site = requireSiteBound(project, environment);
        if (site.isManagedWordpress()) {
            return site;
        }
        SiteService.SiteDataSource dataSource = siteService.resolveDataSource(site);
        boolean usable = isThemeWrite(type) ? dataSource.hasSsh() : (dataSource.hasRest() || dataSource.hasSsh());
        if (usable) {
            return site;
        }
        String reason = isThemeWrite(type)
                ? "はテーマのインストール/有効化/削除に対応するSSH接続設定がないため対象外です"
                        + "(REST APIにはテーマ書き込み用のエンドポイントがありません)"
                : "は自動構築サイトでもREST/SSH接続設定済みサイトでもないため対象外です";
        throw new IllegalArgumentException(environment + "環境(" + site.getSiteKey() + ")" + reason);
    }

    private boolean isThemeWrite(BulkOperationType type) {
        return type == BulkOperationType.THEME_INSTALL || type == BulkOperationType.THEME_ACTIVATE
                || type == BulkOperationType.THEME_DELETE;
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
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }
}
