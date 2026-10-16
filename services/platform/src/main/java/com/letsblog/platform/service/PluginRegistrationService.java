package com.letsblog.platform.service;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.platform.aop.AuditLog;
import com.letsblog.platform.domain.AuditLogAction;
import com.letsblog.platform.domain.PluginKind;
import com.letsblog.platform.domain.PluginRegistration;
import com.letsblog.platform.domain.ProjectPluginRegistration;
import com.letsblog.platform.repository.PluginRegistrationRepository;
import com.letsblog.platform.repository.ProjectPluginRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * システム全体のプラグインの登録(issue #1807、docs/plugins/CONTRACTS.md §4)。管理(一覧・追加・編集・削除・使用中の切り替え)は
 * 管理者限定。解決({@link #resolve})は内部API向けで、環境変数へはフォールバックしない(§4.2)。
 * 接続先は保存時に{@link SystemDestinationPolicy}で検査する(ADR-0013)。
 */
@Service
public class PluginRegistrationService {

    static final String SCOPE_SYSTEM = "SYSTEM";
    static final String SCOPE_PROJECT = "PROJECT";
    private static final int MAX_NAME_LENGTH = 100;
    private static final String URL_LABEL = "接続先";
    private static final String DRAWIO_LABEL = "draw.io";

    /** 管理画面向けの登録。システム全体の登録は認証情報を持たない。 */
    public record RegistrationView(Long id, String kind, String name, String url, boolean inUse, String drawioUrl) {
    }

    /**
     * 内部の解決API向け。{@code connected=false}は未接続(ほかの項目はnull)。{@code scope}は登録の単位(PROJECT / SYSTEM)。
     * {@code credential}は復号した認証情報で、プロジェクトの登録の分だけ(システム全体の登録は認証情報を持たない)。
     */
    public record ResolvedRegistration(
            boolean connected, Long id, String name, String url, String scope, String drawioUrl, String credential) {

        /** 認証情報をログに出さない。 */
        @Override
        public String toString() {
            return "ResolvedRegistration[connected=" + connected + ", id=" + id + ", name=" + name + ", url=" + url
                    + ", scope=" + scope + ", drawioUrl=" + drawioUrl + ", credential=" + (credential == null ? null : "****") + "]";
        }
    }

    private static final ResolvedRegistration NOT_CONNECTED =
            new ResolvedRegistration(false, null, null, null, null, null, null);

    private final PluginRegistrationRepository repository;
    private final ProjectPluginRegistrationRepository projectRepository;
    private final AdminAuthorizationService adminAuthorizationService;
    private final SystemDestinationPolicy destinationPolicy;
    private final CredentialCipher credentialCipher;

    public PluginRegistrationService(
            PluginRegistrationRepository repository,
            ProjectPluginRegistrationRepository projectRepository,
            AdminAuthorizationService adminAuthorizationService,
            SystemDestinationPolicy destinationPolicy,
            CredentialCipher credentialCipher) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        this.adminAuthorizationService = adminAuthorizationService;
        this.destinationPolicy = destinationPolicy;
        this.credentialCipher = credentialCipher;
    }

    @Transactional(readOnly = true)
    public List<RegistrationView> list() {
        adminAuthorizationService.requireAdmin();
        return repository.findAllByOrderByKindAscIdAsc().stream().map(PluginRegistrationService::view).toList();
    }

    @AuditLog(action = AuditLogAction.SYSTEM_SETTING_UPDATED, resourceType = "PLUGIN_REGISTRATION")
    @Transactional
    public RegistrationView create(String kindValue, String name, String url, String drawioUrl) {
        adminAuthorizationService.requireAdmin();
        PluginKind kind = PluginKind.parse(kindValue);
        String cleanName = validatedName(name);
        String cleanUrl = validatedUrl(url);
        String cleanDrawio = validatedDrawioUrl(kind, drawioUrl);
        if (!kind.multiple() && repository.countByKind(kind) >= 1) {
            throw new IllegalArgumentException(
                    kind.displayName() + "の登録は1件までです。追加するには、いまの登録を削除してください");
        }
        return view(repository.save(new PluginRegistration(kind, cleanName, cleanUrl, cleanDrawio)));
    }

    @AuditLog(action = AuditLogAction.SYSTEM_SETTING_UPDATED, resourceType = "PLUGIN_REGISTRATION")
    @Transactional
    public RegistrationView update(Long id, String name, String url, String drawioUrl) {
        adminAuthorizationService.requireAdmin();
        PluginRegistration registration = find(id);
        registration.setName(validatedName(name));
        registration.setBaseUrl(validatedUrl(url));
        registration.setDrawioUrl(validatedDrawioUrl(registration.getKind(), drawioUrl));
        return view(repository.save(registration));
    }

    @AuditLog(action = AuditLogAction.SYSTEM_SETTING_UPDATED, resourceType = "PLUGIN_REGISTRATION")
    @Transactional
    public void delete(Long id) {
        adminAuthorizationService.requireAdmin();
        repository.delete(find(id));
    }

    /** 同じ種類の他の登録の使用中を外し、この登録だけを使用中にする。 */
    @AuditLog(action = AuditLogAction.SYSTEM_SETTING_UPDATED, resourceType = "PLUGIN_REGISTRATION")
    @Transactional
    public RegistrationView activate(Long id) {
        adminAuthorizationService.requireAdmin();
        PluginRegistration target = find(id);
        if (!target.getKind().multiple()) {
            throw new IllegalArgumentException(target.getKind().displayName() + "には使用中の切り替えがありません(登録は1件だけです)");
        }
        for (PluginRegistration other : repository.findByKindOrderByIdAsc(target.getKind())) {
            other.setInUse(other.getId().equals(target.getId()));
            repository.save(other);
        }
        return view(target);
    }

    /**
     * 種類を指定して使用中の登録を返す(内部API。管理者ではなく認証済みのサービスが呼ぶ)。解決順は プロジェクト → システム全体 → 未接続
     * (§4.2。環境変数へはフォールバックしない)。{@code projectId}を指定し、その種類にプロジェクトの上書きがあれば、上書き側の
     * 使用中だけを返す(システム全体とは混ぜない。使用中が無ければ未接続)。Penpotは上書きの対象外でシステム全体だけを見る。
     * LLM・画像生成は使用中の1件、図のレンダリング・Penpotは唯一の登録。
     */
    @Transactional(readOnly = true)
    public ResolvedRegistration resolve(String kindValue, Long projectId) {
        PluginKind kind = PluginKind.parse(kindValue);
        if (projectId != null && kind != PluginKind.PENPOT) {
            List<ProjectPluginRegistration> overrides = projectRepository.findByProjectIdAndKindOrderByIdAsc(projectId, kind);
            if (!overrides.isEmpty()) {
                return resolveOverride(kind, overrides);
            }
        }
        PluginRegistration found = kind.multiple()
                ? repository.findFirstByKindAndInUseTrue(kind).orElse(null)
                : repository.findByKindOrderByIdAsc(kind).stream().findFirst().orElse(null);
        if (found == null) {
            return NOT_CONNECTED;
        }
        return new ResolvedRegistration(
                true, found.getId(), found.getName(), found.getBaseUrl(), SCOPE_SYSTEM, found.getDrawioUrl(), null);
    }

    private ResolvedRegistration resolveOverride(PluginKind kind, List<ProjectPluginRegistration> overrides) {
        ProjectPluginRegistration used = kind.multiple()
                ? overrides.stream().filter(ProjectPluginRegistration::isInUse).findFirst().orElse(null)
                : overrides.get(0);
        if (used == null) {
            return NOT_CONNECTED;
        }
        String credential = used.getCredentialEncrypted() == null ? null : credentialCipher.decrypt(used.getCredentialEncrypted());
        return new ResolvedRegistration(
                true, used.getId(), used.getName(), used.getBaseUrl(), SCOPE_PROJECT, used.getDrawioUrl(), credential);
    }

    private PluginRegistration find(Long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("登録が見つかりません(id=" + id + ")"));
    }

    private static String validatedName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("名前を入力してください");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("名前は" + MAX_NAME_LENGTH + "文字以内にしてください");
        }
        return trimmed;
    }

    private String validatedUrl(String url) {
        destinationPolicy.check(URL_LABEL, url);
        return url.trim();
    }

    /** drawioUrlは図のレンダリングだけが持つ(§3.4)。サーバーが接続しないので形式だけを検査する。空は未指定。 */
    private String validatedDrawioUrl(PluginKind kind, String drawioUrl) {
        if (drawioUrl == null || drawioUrl.isBlank()) {
            return null;
        }
        if (kind != PluginKind.DIAGRAM_RENDERER) {
            throw new IllegalArgumentException("drawioUrl は図のレンダリングだけに指定できます");
        }
        destinationPolicy.checkFormat(DRAWIO_LABEL, drawioUrl);
        return drawioUrl.trim();
    }

    private static RegistrationView view(PluginRegistration r) {
        return new RegistrationView(r.getId(), r.getKind().name(), r.getName(), r.getBaseUrl(), r.isInUse(), r.getDrawioUrl());
    }
}
