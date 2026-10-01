package com.letsblog.identity.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.identity.dto.ProjectUserSyncSiteResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * 監査ログの記録(書き込み)。{@code audit_logs}テーブルの所有権はlog-writerにあり(#572)、
 * プロデューサーはRabbitMQへ流すだけである。media-serviceの同名クラス(#573 stage3)と同じ形。
 *
 * <p>legacy-api版はAOP({@code @AuditLog} + {@code AuditLogAspect})で記録していたが、
 * identity-serviceが記録するアクションは当初{@code project_users}の3つだけだったので、
 * アスペクトごと持ち込まず明示的な呼び出しにした(issue #583)。
 *
 * <p>issue #1137でユーザーの無効化・再有効化・削除・role変更も記録対象に加えたが、
 * #583の判断(AOPを持ち込まない)はそのまま維持する。理由: 対象操作
 * ({@link UserService#deactivate}等)はいずれも戻り値や引数からresourceId
 * (=対象userId)を安全に特定できる単純な形をしており、AOPの汎用的なresourceId推定
 * (戻り値のid/getId、無ければ引数中最初のLong)を持ち込むメリットが薄い一方、
 * publishing/project等の各サービスで実際に汎用アスペクトが必要になったのは
 * 「同種の操作が多数あり、都度手書きすると発行漏れが起きやすい」規模になってからである
 * (#577等)。identity-serviceのusers操作は4種類のみで、明示呼び出しのほうが
 * 「どのメソッドが何を記録するか」をコードレビューで直接確認できる。
 */
@Service
@Slf4j
public class AuditLogService {

    public static final String ACTION_PROJECT_USER_ADDED = "PROJECT_USER_ADDED";
    public static final String ACTION_PROJECT_USER_ROLE_UPDATED = "PROJECT_USER_ROLE_UPDATED";
    public static final String ACTION_PROJECT_USER_REMOVED = "PROJECT_USER_REMOVED";
    /** issue #1242: メンバー個別のユーザー情報再同期。 */
    public static final String ACTION_PROJECT_USER_SYNCED = "PROJECT_USER_SYNCED";

    static final String ACTION_USER_DEACTIVATED = "USER_DEACTIVATED";
    static final String ACTION_USER_REACTIVATED = "USER_REACTIVATED";
    static final String ACTION_USER_DELETED = "USER_DELETED";
    static final String ACTION_USER_ROLE_UPDATED = "USER_ROLE_UPDATED";

    private static final String RESOURCE_TYPE_PROJECT_USER = "PROJECT_USER";
    private static final String RESOURCE_TYPE_USER = "USER";

    private final RabbitTemplate rabbitTemplate;
    private final CurrentActorService currentActorService;
    private final ObjectMapper objectMapper;

    public AuditLogService(
            RabbitTemplate rabbitTemplate, CurrentActorService currentActorService, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.currentActorService = currentActorService;
        this.objectMapper = objectMapper;
    }

    /**
     * {@code project_users}に対する操作を記録する。記録の失敗は呼び出し元の処理を失敗させない
     * (legacy-apiのアスペクトも例外を握り潰してwarnログに留めていた)。
     */
    public void logProjectUserAction(String action, Long projectId) {
        log(action, RESOURCE_TYPE_PROJECT_USER, projectId, null);
    }

    /** issue #1137: ユーザー無効化を記録する。 */
    public void logUserDeactivated(Long userId) {
        log(ACTION_USER_DEACTIVATED, RESOURCE_TYPE_USER, userId, null);
    }

    /** issue #1137: ユーザー再有効化を記録する。 */
    public void logUserReactivated(Long userId) {
        log(ACTION_USER_REACTIVATED, RESOURCE_TYPE_USER, userId, null);
    }

    /** issue #1137: ユーザー削除を記録する。 */
    public void logUserDeleted(Long userId) {
        log(ACTION_USER_DELETED, RESOURCE_TYPE_USER, userId, null);
    }

    /**
     * issue #1137: ユーザーのrole変更を記録する。{@code changes}に変更前後のroleを
     * JSONで入れる(要件2)。JSON化に失敗しても記録自体は続行する(nullのまま発行する)。
     */
    public void logUserRoleUpdated(Long userId, String oldRole, String newRole) {
        log(ACTION_USER_ROLE_UPDATED, RESOURCE_TYPE_USER, userId, roleChangeJson(oldRole, newRole));
    }

    private String roleChangeJson(String oldRole, String newRole) {
        try {
            return objectMapper.writeValueAsString(Map.of("role", Map.of("from", oldRole, "to", newRole)));
        } catch (Exception e) {
            log.warn("role変更の監査ログchangesのJSON化に失敗しました: oldRole={}, newRole={}", oldRole, newRole, e);
            return null;
        }
    }

    /**
     * 監査ログを1件記録する。記録の失敗は呼び出し元の業務操作を失敗させない
     * (legacy-apiのアスペクトも例外を握り潰してwarnログに留めていた。要件3)。
     */
    private void log(String action, String resourceType, Long resourceId, String changes) {
        try {
            AuditLogMessage message = new AuditLogMessage(
                    currentActorService.getCurrentActorId(),
                    currentActorService.getCurrentActorKeycloakSub(),
                    action,
                    resourceType,
                    resourceId,
                    changes,
                    currentActorService.getRemoteIp(),
                    currentActorService.getUserAgent(),
                    LocalDateTime.now().toString());
            rabbitTemplate.convertAndSend(LogExchanges.LOG_EXCHANGE, LogExchanges.AUDIT_LOG_ROUTING_KEY, message);
            log.info("Audit log published to queue: action={}, resource={}/{}", action, resourceType, resourceId);
        } catch (RuntimeException e) {
            log.warn("監査ログの記録に失敗しました: action={}, resource={}/{}", action, resourceType, resourceId, e);
        }
    }

    /**
     * issue #1242要件5: メンバー個別のユーザー情報再同期の実行結果(対象メンバー、対象プロジェクト、
     * 成功/失敗した環境)を監査ログに記録する。{@code changes}には対象メンバーとサイトごとの
     * 成否・失敗理由をJSONで残す({@link #logProjectUserAction}の対象3操作は戻り値がvoidのため
     * 常にnullだったが、この操作は「何が起きたか」自体が記録の主眼のため明示的に持たせる)。
     */
    public void logProjectUserSyncAction(Long projectId, Long userId, List<ProjectUserSyncSiteResult> results) {
        try {
            String changes = objectMapper.writeValueAsString(new SyncChanges(userId, results));
            AuditLogMessage message = new AuditLogMessage(
                    currentActorService.getCurrentActorId(),
                    currentActorService.getCurrentActorKeycloakSub(),
                    ACTION_PROJECT_USER_SYNCED,
                    RESOURCE_TYPE_PROJECT_USER,
                    projectId,
                    changes,
                    currentActorService.getRemoteIp(),
                    currentActorService.getUserAgent(),
                    LocalDateTime.now().toString());
            rabbitTemplate.convertAndSend(LogExchanges.LOG_EXCHANGE, LogExchanges.AUDIT_LOG_ROUTING_KEY, message);
            log.info(
                    "Audit log published to queue: action={}, projectId={}, userId={}",
                    ACTION_PROJECT_USER_SYNCED, projectId, userId);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn(
                    "監査ログの記録に失敗しました: action={}, projectId={}, userId={}",
                    ACTION_PROJECT_USER_SYNCED, projectId, userId, e);
        }
    }

    private record SyncChanges(Long userId, List<ProjectUserSyncSiteResult> results) {
    }
}
