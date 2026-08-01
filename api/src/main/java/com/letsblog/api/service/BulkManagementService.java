package com.letsblog.api.service;

import com.letsblog.api.domain.BulkOperationLog;
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
import org.springframework.stereotype.Service;
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
public class BulkManagementService {

    private static final List<String> ENVIRONMENT_ORDER = List.of("local", "test", "production");
    private static final Set<String> VALID_ENVIRONMENTS = Set.of("local", "test", "production");

    private final ProjectRepository projectRepository;
    private final SiteRepository siteRepository;
    private final BulkOperationLogRepository bulkOperationLogRepository;
    private final WordPressBulkManagementClient bulkManagementClient;
    private final BulkUploadStorageService bulkUploadStorageService;

    public BulkManagementService(
            ProjectRepository projectRepository,
            SiteRepository siteRepository,
            BulkOperationLogRepository bulkOperationLogRepository,
            WordPressBulkManagementClient bulkManagementClient,
            BulkUploadStorageService bulkUploadStorageService) {
        this.projectRepository = projectRepository;
        this.siteRepository = siteRepository;
        this.bulkOperationLogRepository = bulkOperationLogRepository;
        this.bulkManagementClient = bulkManagementClient;
        this.bulkUploadStorageService = bulkUploadStorageService;
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
        Site site = resolveManagedSite(project, environment);
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
        List<Map.Entry<String, Site>> environments = resolveManagedEnvironments(project);

        byte[] bytes = file.getBytes();
        BulkUploadStorageService.StoredZip stored =
                bulkUploadStorageService.store(projectId, bytes, file.getOriginalFilename());

        List<BulkOperationLog> results = new ArrayList<>();
        for (Map.Entry<String, Site> entry : environments) {
            WordPressBulkManagementClient.BulkApplyResult result = bulkManagementClient.applyZip(
                    entry.getValue().getWpSlug(), type.wpCliAction(), bytes, stored.originalFilename());
            results.add(saveLog(projectId, type, BulkOperationSourceType.ZIP, stored.originalFilename(),
                    null, null, null, null,
                    stored.originalFilename(), stored.storagePath(), stored.sha256(),
                    entry.getKey(), result, actorId, false));
        }
        return results;
    }

    @Transactional
    public List<BulkOperationLog> replay(Long projectId, String environment, Long actorId) {
        Project project = getProject(projectId);
        Site targetSite = resolveManagedSite(project, environment);
        List<BulkOperationLog> history = bulkOperationLogRepository
                .findByProjectIdAndStatusOrderByCreatedAtAsc(projectId, BulkOperationStatus.SUCCESS);

        List<BulkOperationLog> results = new ArrayList<>();
        for (BulkOperationLog log : history) {
            if (log.getSourceType() == BulkOperationSourceType.ZIP) {
                results.add(replayZip(projectId, environment, targetSite, log, actorId));
            } else {
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

    private BulkOperationLog applyToSite(
            Long projectId, String environment, Site site, BulkOperationType type, String value,
            String categorySlug, String categoryParentSlug, String categoryDescription, String categoryTargetSlug,
            Long actorId, boolean isReplay) {
        WordPressBulkManagementClient.BulkApplyResult result = bulkManagementClient.apply(new BulkApplyCommand(
                site.getWpSlug(), type.wpCliAction(), value,
                categorySlug, categoryParentSlug, categoryDescription, categoryTargetSlug));
        return saveLog(projectId, type, BulkOperationSourceType.SLUG, value,
                categorySlug, categoryParentSlug, categoryTargetSlug, categoryDescription, null, null, null,
                environment, result, actorId, isReplay);
    }

    private BulkOperationLog replayZip(
            Long projectId, String environment, Site targetSite, BulkOperationLog log, Long actorId) {
        WordPressBulkManagementClient.BulkApplyResult result;
        try {
            byte[] bytes = bulkUploadStorageService.load(log.getStoragePath());
            result = bulkManagementClient.applyZip(
                    targetSite.getWpSlug(), log.getOperationType().wpCliAction(), bytes, log.getOriginalFilename());
        } catch (IOException e) {
            result = WordPressBulkManagementClient.BulkApplyResult.failed(
                    "元ファイルが見つかりません。再度アップロードしてください: " + e.getMessage());
        }
        return saveLog(projectId, log.getOperationType(), log.getSourceType(), log.getValue(),
                log.getCategorySlug(), log.getCategoryParentSlug(), log.getCategoryTargetSlug(),
                log.getCategoryDescription(),
                log.getOriginalFilename(), log.getStoragePath(), log.getFileSha256(),
                environment, result, actorId, true);
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
            WordPressBulkManagementClient.BulkApplyResult result, Long actorId, boolean isReplay) {
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
        log.setEnvironment(environment);
        log.setStatus(BulkOperationStatus.valueOf(result.status()));
        log.setErrorMessage(result.errorMessage());
        log.setReplay(isReplay);
        log.setActorId(actorId);
        return bulkOperationLogRepository.save(log);
    }

    private List<Map.Entry<String, Site>> resolveManagedEnvironments(Project project) {
        List<Map.Entry<String, Site>> result = new ArrayList<>();
        for (String environment : ENVIRONMENT_ORDER) {
            Long siteId = siteIdOf(project, environment);
            if (siteId == null) {
                continue;
            }
            Site site = siteRepository.findById(siteId).orElse(null);
            if (site != null && site.isManagedWordpress()) {
                result.add(new AbstractMap.SimpleEntry<>(environment, site));
            }
        }
        return result;
    }

    private Site resolveManagedSite(Project project, String environment) {
        if (!VALID_ENVIRONMENTS.contains(environment)) {
            throw new IllegalArgumentException("environment は local/test/production のいずれかを指定してください");
        }
        Long siteId = siteIdOf(project, environment);
        if (siteId == null) {
            throw new IllegalArgumentException(environment + "環境にはサイトが紐付けられていません");
        }
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new SiteNotFoundException("id " + siteId + " のサイトは登録されていません"));
        if (!site.isManagedWordpress()) {
            throw new IllegalArgumentException(
                    environment + "環境(" + site.getSiteKey() + ")は自動構築サイトではないため対象外です");
        }
        return site;
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
