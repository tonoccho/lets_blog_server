package com.letsblog.platform.service;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.common.net.ConnectionDestinationGuard;
import com.letsblog.common.net.InterfaceAddr;
import com.letsblog.platform.domain.PluginKind;
import com.letsblog.platform.domain.ProjectPluginRegistration;
import com.letsblog.platform.repository.ProjectPluginRegistrationRepository;
import com.letsblog.platform.service.ProjectPluginRegistrationService.ProjectRegistrationView;
import com.letsblog.platform.testsupport.PlatformWorld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** プロジェクト単位のプラグインの登録の上書き(issue #1808)。認可・件数の規則・保存時の検査・認証情報の暗号化。 */
@ExtendWith(MockitoExtension.class)
class ProjectPluginRegistrationServiceTest {

    private static final long PROJECT = 10L;

    @Mock
    private ProjectPluginRegistrationRepository repository;
    /** identity-serviceはHTTPのフェイクで模し、AdminAuthorizationServiceは実物を通す(issue #1825)。既定はadmin。 */
    private final PlatformWorld world = new PlatformWorld().actingAsAdmin(1L);
    private final AdminAuthorizationService adminAuthorizationService = world.adminAuthorizationService;

    @org.junit.jupiter.api.AfterEach
    void closeWorld() {
        world.close();
    }

    private final CredentialCipher cipher = new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private ProjectPluginRegistrationService service;
    private long nextId;

    @BeforeEach
    void setUp() {
        // 自プロセスはdockerネットワーク 172.20.0.0/16 に参加している。lbx-ollama はその中、gpu-pc は外のLANに解決される。
        ConnectionDestinationGuard guard = new ConnectionDestinationGuard(
                host -> switch (host) {
                    case "lbx-ollama" -> new InetAddress[] {InetAddress.getByName("172.20.0.9")};
                    case "gpu-pc.example" -> new InetAddress[] {InetAddress.getByName("192.168.1.50")};
                    case "loopback.example" -> new InetAddress[] {InetAddress.getByName("127.0.0.1")};
                    default -> throw new UnknownHostException(host);
                },
                () -> {
                    try {
                        return List.of(new InterfaceAddr(InetAddress.getByName("172.20.0.2").getAddress(), 16));
                    } catch (UnknownHostException e) {
                        throw new IllegalStateException(e);
                    }
                },
                () -> true);
        service = new ProjectPluginRegistrationService(repository, adminAuthorizationService, guard, cipher);
        nextId = 1;
        lenient().when(repository.save(any(ProjectPluginRegistration.class))).thenAnswer(inv -> {
            ProjectPluginRegistration r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(nextId++);
            }
            return r;
        });
    }

    private ProjectPluginRegistration existing(long id, PluginKind kind, String name, boolean inUse) {
        ProjectPluginRegistration r = new ProjectPluginRegistration(PROJECT, kind, name, "http://gpu-pc.example:1", null);
        r.setId(id);
        r.setInUse(inUse);
        return r;
    }

    @Test
    void 追加はプロジェクトのメンバーまたは管理者を要求し_認証情報を暗号化して保存する() {
        ProjectRegistrationView view =
                service.create(PROJECT, "LLM", " 社内のOllama ", "http://gpu-pc.example:11434/v1", null, "sk-secret");

        assertEquals(1, world.http.requests("GET", "/api/identity/me").size());
        assertEquals(new ProjectRegistrationView(1L, "LLM", "社内のOllama", "http://gpu-pc.example:11434/v1", false, null, true), view);
        org.mockito.ArgumentCaptor<ProjectPluginRegistration> saved = org.mockito.ArgumentCaptor.forClass(ProjectPluginRegistration.class);
        verify(repository).save(saved.capture());
        assertEquals(PROJECT, saved.getValue().getProjectId());
        byte[] stored = saved.getValue().getCredentialEncrypted();
        assertNotNull(stored);
        assertFalse(new String(stored).contains("sk-secret"));
        assertEquals("sk-secret", cipher.decrypt(stored));
    }

    @Test
    void 認証情報を省略した登録は設定済みにならない() {
        assertFalse(service.create(PROJECT, "LLM", "a", "http://gpu-pc.example:1", null, null).credentialConfigured());
        assertFalse(service.create(PROJECT, "LLM", "b", "http://gpu-pc.example:1", null, "  ").credentialConfigured());
    }

    @Test
    void メンバーでなければ何も保存せず_リポジトリにも触れない() {
        world.actingAsUser(2L).projectMember(PROJECT, 2L, false);

        assertThrows(ForbiddenException.class, () -> service.create(PROJECT, "LLM", "a", "http://gpu-pc.example:1", null, null));
        assertThrows(ForbiddenException.class, () -> service.list(PROJECT));
        assertThrows(ForbiddenException.class, () -> service.update(PROJECT, 1L, "a", "http://gpu-pc.example:1", null, null));
        assertThrows(ForbiddenException.class, () -> service.delete(PROJECT, 1L));
        assertThrows(ForbiddenException.class, () -> service.activate(PROJECT, 1L));
        assertThrows(ForbiddenException.class, () -> service.clearCredential(PROJECT, 1L));
        verifyNoInteractions(repository);
    }

    @Test
    void Penpotは上書きの対象にできない() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(PROJECT, "PENPOT", "p", "http://gpu-pc.example:1", null, null));
        assertTrue(e.getMessage().contains("Penpot"), e.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    void 禁止アドレス_dockerネットワーク内_名前解決できないホスト_http以外は理由つきで保存しない() {
        IllegalArgumentException docker = assertThrows(IllegalArgumentException.class,
                () -> service.create(PROJECT, "LLM", "a", "http://lbx-ollama:11434", null, null));
        assertTrue(docker.getMessage().contains("サーバ内部のネットワーク"), docker.getMessage());
        for (String url : new String[] {"http://127.0.0.1:11434", "http://169.254.169.254/", "http://loopback.example",
                "http://unknown.invalid", "ftp://", "ftp://gpu-pc.example:1", "not a url"}) {
            assertThrows(IllegalArgumentException.class, () -> service.create(PROJECT, "LLM", "a", url, null, null), url);
        }
        verify(repository, never()).save(any());
    }

    @Test
    void 種類が不明_名前が空_名前が長すぎる_URLが空は拒否する() {
        assertThrows(IllegalArgumentException.class, () -> service.create(PROJECT, "BOGUS", "a", "http://gpu-pc.example:1", null, null));
        assertThrows(IllegalArgumentException.class, () -> service.create(PROJECT, "LLM", " ", "http://gpu-pc.example:1", null, null));
        assertThrows(IllegalArgumentException.class, () -> service.create(PROJECT, "LLM", null, "http://gpu-pc.example:1", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(PROJECT, "LLM", "あ".repeat(101), "http://gpu-pc.example:1", null, null));
        assertThrows(IllegalArgumentException.class, () -> service.create(PROJECT, "LLM", "a", null, null, null));
        service.create(PROJECT, "LLM", "あ".repeat(100), "http://gpu-pc.example:1", null, null);
    }

    @Test
    void 図のレンダリングの2件目は拒否し_LLMは複数登録できる() {
        when(repository.countByProjectIdAndKind(PROJECT, PluginKind.DIAGRAM_RENDERER)).thenReturn(1L);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(PROJECT, "DIAGRAM_RENDERER", "b", "http://gpu-pc.example:1", null, null));
        assertTrue(e.getMessage().contains("1件まで"), e.getMessage());
        service.create(PROJECT, "LLM", "6件目", "http://gpu-pc.example:1", null, null);
    }

    @Test
    void drawioUrlは図のレンダリングだけが持て_形式だけを検査する() {
        when(repository.countByProjectIdAndKind(PROJECT, PluginKind.DIAGRAM_RENDERER)).thenReturn(0L);

        assertEquals("https://drawio.example.com/", service.create(
                PROJECT, "DIAGRAM_RENDERER", "図", "http://gpu-pc.example:8085", "https://drawio.example.com/", null).drawioUrl());
        assertThrows(IllegalArgumentException.class,
                () -> service.create(PROJECT, "DIAGRAM_RENDERER", "図", "http://gpu-pc.example:8085", "ftp://x", null));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.create(PROJECT, "LLM", "a", "http://gpu-pc.example:1", "https://drawio.example.com/", null))
                .getMessage().contains("drawioUrl"));
        assertNull(service.create(PROJECT, "DIAGRAM_RENDERER", "図", "http://gpu-pc.example:8085", "  ", null).drawioUrl());
    }

    @Test
    void 一覧はこのプロジェクトの登録を返し_認証情報の値は含めず設定済みだけを示す() {
        ProjectPluginRegistration withKey = existing(1, PluginKind.LLM, "a", true);
        withKey.setCredentialEncrypted(cipher.encrypt("sk-secret"));
        when(repository.findByProjectIdOrderByKindAscIdAsc(PROJECT)).thenReturn(List.of(withKey, existing(2, PluginKind.IMAGE_GENERATION, "b", false)));

        List<ProjectRegistrationView> views = service.list(PROJECT);

        assertEquals(2, views.size());
        assertTrue(views.get(0).credentialConfigured());
        assertFalse(views.get(1).credentialConfigured());
        assertFalse(views.toString().contains("sk-secret"));
    }

    @Test
    void 編集は名前とURLを検査して更新し_認証情報は空なら変えず_指定があれば置き換える() {
        ProjectPluginRegistration r = existing(3, PluginKind.LLM, "旧", true);
        r.setCredentialEncrypted(cipher.encrypt("old-key"));
        when(repository.findByIdAndProjectId(3L, PROJECT)).thenReturn(Optional.of(r));

        ProjectRegistrationView kept = service.update(PROJECT, 3L, "新", "http://gpu-pc.example:2", null, null);
        assertEquals("新", kept.name());
        assertEquals("http://gpu-pc.example:2", kept.url());
        assertTrue(kept.inUse());
        assertEquals("old-key", cipher.decrypt(r.getCredentialEncrypted()));

        service.update(PROJECT, 3L, "新", "http://gpu-pc.example:2", null, "  ");
        assertEquals("old-key", cipher.decrypt(r.getCredentialEncrypted()));

        service.update(PROJECT, 3L, "新", "http://gpu-pc.example:2", null, "new-key");
        assertEquals("new-key", cipher.decrypt(r.getCredentialEncrypted()));

        assertThrows(IllegalArgumentException.class, () -> service.update(PROJECT, 3L, "新", "http://lbx-ollama:2", null, null));
        assertEquals("http://gpu-pc.example:2", r.getBaseUrl());
    }

    @Test
    void 図のレンダリングの編集ではdrawioUrlを更新し_他の種類では指定を拒否する() {
        ProjectPluginRegistration diagram = existing(4, PluginKind.DIAGRAM_RENDERER, "図", false);
        ProjectPluginRegistration llm = existing(5, PluginKind.LLM, "a", false);
        when(repository.findByIdAndProjectId(4L, PROJECT)).thenReturn(Optional.of(diagram));
        when(repository.findByIdAndProjectId(5L, PROJECT)).thenReturn(Optional.of(llm));

        assertEquals("https://d.example/", service.update(PROJECT, 4L, "図", "http://gpu-pc.example:1", "https://d.example/", null).drawioUrl());
        assertNull(service.update(PROJECT, 4L, "図", "http://gpu-pc.example:1", null, null).drawioUrl());
        assertThrows(IllegalArgumentException.class,
                () -> service.update(PROJECT, 5L, "a", "http://gpu-pc.example:1", "https://d.example/", null));
    }

    @Test
    void 認証情報を消去できる() {
        ProjectPluginRegistration r = existing(3, PluginKind.LLM, "a", true);
        r.setCredentialEncrypted(cipher.encrypt("sk-secret"));
        when(repository.findByIdAndProjectId(3L, PROJECT)).thenReturn(Optional.of(r));

        ProjectRegistrationView view = service.clearCredential(PROJECT, 3L);

        assertFalse(view.credentialConfigured());
        assertNull(r.getCredentialEncrypted());
        verify(repository).save(r);
    }

    @Test
    void 他のプロジェクトの登録や存在しないIDの編集_削除_使用中の切り替え_認証情報の消去は拒否する() {
        when(repository.findByIdAndProjectId(9L, PROJECT)).thenReturn(Optional.empty());

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.update(PROJECT, 9L, "a", "http://gpu-pc.example:1", null, null)).getMessage().contains("見つかりません"));
        assertThrows(IllegalArgumentException.class, () -> service.delete(PROJECT, 9L));
        assertThrows(IllegalArgumentException.class, () -> service.activate(PROJECT, 9L));
        assertThrows(IllegalArgumentException.class, () -> service.clearCredential(PROJECT, 9L));
        verify(repository, never()).delete(any());
    }

    @Test
    void 削除すると行が消える() {
        ProjectPluginRegistration r = existing(3, PluginKind.LLM, "a", true);
        when(repository.findByIdAndProjectId(3L, PROJECT)).thenReturn(Optional.of(r));

        service.delete(PROJECT, 3L);

        verify(repository).delete(r);
    }

    @Test
    void 使用中の切り替えは同じプロジェクト_同じ種類の他の登録を外し_1件だけにする() {
        ProjectPluginRegistration a = existing(1, PluginKind.LLM, "a", true);
        ProjectPluginRegistration b = existing(2, PluginKind.LLM, "b", false);
        when(repository.findByIdAndProjectId(2L, PROJECT)).thenReturn(Optional.of(b));
        when(repository.findByProjectIdAndKindOrderByIdAsc(PROJECT, PluginKind.LLM)).thenReturn(new ArrayList<>(List.of(a, b)));

        ProjectRegistrationView view = service.activate(PROJECT, 2L);

        assertTrue(view.inUse());
        assertFalse(a.isInUse());
        assertTrue(b.isInUse());
    }

    @Test
    void 図のレンダリングに使用中の切り替えは無い() {
        when(repository.findByIdAndProjectId(4L, PROJECT)).thenReturn(Optional.of(existing(4, PluginKind.DIAGRAM_RENDERER, "図", false)));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.activate(PROJECT, 4L)).getMessage().contains("使用中"));
    }

    @Test
    void drawioUrlのホストが無い_解釈できないURLは理由つきで拒否する() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.create(
                PROJECT, "DIAGRAM_RENDERER", "図", "http://gpu-pc.example:8085", "http:///no-host", null))
                .getMessage().contains("ホストがありません"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.create(
                PROJECT, "DIAGRAM_RENDERER", "図", "http://gpu-pc.example:8085", "http://a b", null))
                .getMessage().contains("解釈できません"));
    }
}
