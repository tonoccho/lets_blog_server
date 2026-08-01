package com.letsblog.api.service;

import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationSourceType;
import com.letsblog.api.domain.BulkOperationStatus;
import com.letsblog.api.domain.BulkOperationType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.provisioning.WordPressBulkManagementClient;
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
 * プロジェクトに紐づくmanagedWordpress環境すべてへ、カテゴリ作成・プラグイン/テーマインストールを
 * 一括実行する。1環境の失敗が他環境の実行を止めないよう、環境ごとに結果を個別に記録する。
 * 実行内容はbulk_operation_logsへ保存し、任意の1環境へのロールフォワード(過去の成功ログの再適用)に使う。
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

    @Transactional
    public List<BulkOperationLog> execute(Long projectId, BulkOperationType type, String value, Long actorId) {
        Project project = getProject(projectId);
        List<Map.Entry<String, Site>> environments = resolveManagedEnvironments(project);

        List<BulkOperationLog> results = new ArrayList<>();
        for (Map.Entry<String, Site> entry : environments) {
            WordPressBulkManagementClient.BulkApplyResult result =
                    bulkManagementClient.apply(entry.getValue().getWpSlug(), type.wpCliAction(), value);
            results.add(saveLog(projectId, type, BulkOperationSourceType.SLUG, value, null, null, null,
                    entry.getKey(), result, actorId, false));
        }
        return results;
    }

    @Transactional
    public List<BulkOperationLog> executeFromUpload(
            Long projectId, BulkOperationType type, MultipartFile file, Long actorId) throws IOException {
        if (type == BulkOperationType.CATEGORY) {
            throw new IllegalArgumentException("zipアップロードはプラグイン/テーマのみ対応しています");
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
            WordPressBulkManagementClient.BulkApplyResult result = applyFromHistory(log, targetSite);
            results.add(saveLog(projectId, log.getOperationType(), log.getSourceType(), log.getValue(),
                    log.getOriginalFilename(), log.getStoragePath(), log.getFileSha256(),
                    environment, result, actorId, true));
        }
        return results;
    }

    @Transactional(readOnly = true)
    public List<BulkOperationLog> listLogs(Long projectId) {
        return bulkOperationLogRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    private WordPressBulkManagementClient.BulkApplyResult applyFromHistory(BulkOperationLog log, Site targetSite) {
        if (log.getSourceType() == BulkOperationSourceType.ZIP) {
            try {
                byte[] bytes = bulkUploadStorageService.load(log.getStoragePath());
                return bulkManagementClient.applyZip(
                        targetSite.getWpSlug(), log.getOperationType().wpCliAction(), bytes, log.getOriginalFilename());
            } catch (IOException e) {
                return WordPressBulkManagementClient.BulkApplyResult.failed(
                        "元ファイルが見つかりません。再度アップロードしてください: " + e.getMessage());
            }
        }
        return bulkManagementClient.apply(targetSite.getWpSlug(), log.getOperationType().wpCliAction(), log.getValue());
    }

    private BulkOperationLog saveLog(
            Long projectId, BulkOperationType type, BulkOperationSourceType sourceType, String value,
            String originalFilename, String storagePath, String fileSha256, String environment,
            WordPressBulkManagementClient.BulkApplyResult result, Long actorId, boolean isReplay) {
        BulkOperationLog log = new BulkOperationLog();
        log.setProjectId(projectId);
        log.setOperationType(type);
        log.setSourceType(sourceType);
        log.setValue(value);
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
