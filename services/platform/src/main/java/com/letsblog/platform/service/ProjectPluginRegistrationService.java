package com.letsblog.platform.service;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.common.net.ConnectionDestinationGuard;
import com.letsblog.common.net.ForbiddenDestinationException;
import com.letsblog.platform.aop.AuditLog;
import com.letsblog.platform.domain.AuditLogAction;
import com.letsblog.platform.domain.PluginKind;
import com.letsblog.platform.domain.ProjectPluginRegistration;
import com.letsblog.platform.repository.ProjectPluginRegistrationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * プロジェクト単位のプラグインの登録の上書き(issue #1808、docs/plugins/CONTRACTS.md §4.2)。
 * 管理(一覧・追加・編集・削除・使用中の切り替え・認証情報の消去)はプロジェクトのメンバーまたは管理者
 * ({@code requireProjectMemberOrAdmin}。現行のプロジェクトのAPIキーと同じ)。Penpotは上書きの対象にしない。
 *
 * <p>接続先はシステム全体の登録({@link SystemDestinationPolicy}。dockerネットワーク内を許す)と違い、現行のプロジェクトの
 * {@code ollama_base_url}と同じ{@link ConnectionDestinationGuard}全体(禁止アドレスに加えdockerネットワーク内も拒否)で検査する。
 * 認証情報は{@link CredentialCipher}で暗号化して保存し、一覧には「設定済み」かどうかだけを出す(値は返さない)。
 * 解決(上書き → システム全体 → 未接続)は{@link PluginRegistrationService#resolve}が担う。
 */
@Service
public class ProjectPluginRegistrationService {

    private static final int MAX_NAME_LENGTH = 100;
    private static final Pattern HTTP_SCHEME = Pattern.compile("(?i)https?");
    private static final String DRAWIO_LABEL = "draw.io";

    /** 画面・取得API向けの上書き。認証情報の値は含めず、設定済みかどうかだけを示す。 */
    public record ProjectRegistrationView(
            Long id, String kind, String name, String url, boolean inUse, String drawioUrl, boolean credentialConfigured) {
    }

    private final ProjectPluginRegistrationRepository repository;
    private final AdminAuthorizationService adminAuthorizationService;
    private final ConnectionDestinationGuard destinationGuard;
    private final CredentialCipher credentialCipher;

    @Autowired
    public ProjectPluginRegistrationService(
            ProjectPluginRegistrationRepository repository,
            AdminAuthorizationService adminAuthorizationService,
            CredentialCipher credentialCipher) {
        this(repository, adminAuthorizationService, ConnectionDestinationGuard.system(), credentialCipher);
    }

    ProjectPluginRegistrationService(
            ProjectPluginRegistrationRepository repository,
            AdminAuthorizationService adminAuthorizationService,
            ConnectionDestinationGuard destinationGuard,
            CredentialCipher credentialCipher) {
        this.repository = repository;
        this.adminAuthorizationService = adminAuthorizationService;
        this.destinationGuard = destinationGuard;
        this.credentialCipher = credentialCipher;
    }

    @Transactional(readOnly = true)
    public List<ProjectRegistrationView> list(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return repository.findByProjectIdOrderByKindAscIdAsc(projectId).stream()
                .map(ProjectPluginRegistrationService::view).toList();
    }

    @AuditLog(action = AuditLogAction.SYSTEM_SETTING_UPDATED, resourceType = "PROJECT_PLUGIN_REGISTRATION")
    @Transactional
    public ProjectRegistrationView create(
            Long projectId, String kindValue, String name, String url, String drawioUrl, String credential) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        PluginKind kind = PluginKind.parse(kindValue);
        if (kind == PluginKind.PENPOT) {
            throw new IllegalArgumentException("Penpotはプロジェクト単位の上書きにできません(接続先はシステム全体に1つです)");
        }
        String cleanName = validatedName(name);
        String cleanUrl = validatedUrl(kind, url);
        String cleanDrawio = validatedDrawioUrl(kind, drawioUrl);
        if (!kind.multiple() && repository.countByProjectIdAndKind(projectId, kind) >= 1) {
            throw new IllegalArgumentException(
                    kind.displayName() + "の上書きは1件までです。追加するには、いまの上書きを削除してください");
        }
        ProjectPluginRegistration registration =
                new ProjectPluginRegistration(projectId, kind, cleanName, cleanUrl, cleanDrawio);
        if (hasText(credential)) {
            registration.setCredentialEncrypted(credentialCipher.encrypt(credential.trim()));
        }
        return view(repository.save(registration));
    }

    /** 認証情報が空(または未指定)なら保存済みの値を変えない。消去は{@link #clearCredential}。 */
    @AuditLog(action = AuditLogAction.SYSTEM_SETTING_UPDATED, resourceType = "PROJECT_PLUGIN_REGISTRATION")
    @Transactional
    public ProjectRegistrationView update(
            Long projectId, Long id, String name, String url, String drawioUrl, String credential) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        ProjectPluginRegistration registration = find(projectId, id);
        registration.setName(validatedName(name));
        registration.setBaseUrl(validatedUrl(registration.getKind(), url));
        registration.setDrawioUrl(validatedDrawioUrl(registration.getKind(), drawioUrl));
        if (hasText(credential)) {
            registration.setCredentialEncrypted(credentialCipher.encrypt(credential.trim()));
        }
        return view(repository.save(registration));
    }

    @AuditLog(action = AuditLogAction.SYSTEM_SETTING_UPDATED, resourceType = "PROJECT_PLUGIN_REGISTRATION")
    @Transactional
    public void delete(Long projectId, Long id) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        repository.delete(find(projectId, id));
    }

    /** 同じプロジェクト・同じ種類の他の上書きの使用中を外し、この上書きだけを使用中にする。 */
    @AuditLog(action = AuditLogAction.SYSTEM_SETTING_UPDATED, resourceType = "PROJECT_PLUGIN_REGISTRATION")
    @Transactional
    public ProjectRegistrationView activate(Long projectId, Long id) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        ProjectPluginRegistration target = find(projectId, id);
        if (!target.getKind().multiple()) {
            throw new IllegalArgumentException(target.getKind().displayName() + "には使用中の切り替えがありません(上書きは1件だけです)");
        }
        for (ProjectPluginRegistration other : repository.findByProjectIdAndKindOrderByIdAsc(projectId, target.getKind())) {
            other.setInUse(other.getId().equals(target.getId()));
            repository.save(other);
        }
        return view(target);
    }

    @AuditLog(action = AuditLogAction.SYSTEM_SETTING_UPDATED, resourceType = "PROJECT_PLUGIN_REGISTRATION")
    @Transactional
    public ProjectRegistrationView clearCredential(Long projectId, Long id) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        ProjectPluginRegistration registration = find(projectId, id);
        registration.setCredentialEncrypted(null);
        return view(repository.save(registration));
    }

    private ProjectPluginRegistration find(Long projectId, Long id) {
        return repository.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new IllegalArgumentException("上書きが見つかりません(id=" + id + ")"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String validatedName(String name) {
        if (!hasText(name)) {
            throw new IllegalArgumentException("名前を入力してください");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("名前は" + MAX_NAME_LENGTH + "文字以内にしてください");
        }
        return trimmed;
    }

    /** http(s)のURLで、ホストが{@link ConnectionDestinationGuard}を通ること(禁止アドレス・dockerネットワーク内・解決不能は拒否)。 */
    private String validatedUrl(PluginKind kind, String url) {
        String label = kind.displayName();
        if (!hasText(url)) {
            throw new IllegalArgumentException(label + "の接続先のURLを指定してください");
        }
        String trimmed = url.trim();
        requireHttpUrl(label + "の接続先", trimmed);
        try {
            destinationGuard.check(label, trimmed);
        } catch (ForbiddenDestinationException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        return trimmed;
    }

    /** drawioUrlは図のレンダリングだけが持つ(§3.4)。利用者のブラウザが開くURLなので、形式だけを検査する。空は未指定。 */
    private String validatedDrawioUrl(PluginKind kind, String drawioUrl) {
        if (!hasText(drawioUrl)) {
            return null;
        }
        if (kind != PluginKind.DIAGRAM_RENDERER) {
            throw new IllegalArgumentException("drawioUrl は図のレンダリングだけに指定できます");
        }
        requireHttpUrl(DRAWIO_LABEL, drawioUrl.trim());
        return drawioUrl.trim();
    }

    private static void requireHttpUrl(String label, String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(label + "のURLを解釈できません", e);
        }
        if (!HTTP_SCHEME.matcher(String.valueOf(uri.getScheme())).matches()) {
            throw new IllegalArgumentException(label + "のURLはhttp://またはhttps://から始めてください");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException(label + "のURLにホストがありません");
        }
    }

    private static ProjectRegistrationView view(ProjectPluginRegistration r) {
        return new ProjectRegistrationView(r.getId(), r.getKind().name(), r.getName(), r.getBaseUrl(), r.isInUse(),
                r.getDrawioUrl(), r.getCredentialEncrypted() != null);
    }
}
