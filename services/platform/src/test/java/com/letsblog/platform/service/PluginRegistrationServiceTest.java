package com.letsblog.platform.service;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.platform.domain.PluginKind;
import com.letsblog.platform.domain.ProjectPluginRegistration;
import com.letsblog.platform.repository.ProjectPluginRegistrationRepository;
import com.letsblog.platform.domain.PluginRegistration;
import com.letsblog.platform.repository.PluginRegistrationRepository;
import com.letsblog.platform.service.PluginRegistrationService.RegistrationView;
import com.letsblog.platform.service.PluginRegistrationService.ResolvedRegistration;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/** システム全体のプラグインの登録(issue #1807)。件数の規則・保存時の検査・使用中の切り替え・内部の解決。 */
@ExtendWith(MockitoExtension.class)
class PluginRegistrationServiceTest {

    @Mock
    private PluginRegistrationRepository repository;
    @Mock
    private ProjectPluginRegistrationRepository projectRepository;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private final CredentialCipher cipher = new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private PluginRegistrationService service;
    private long nextId;

    @BeforeEach
    void setUp() {
        SystemDestinationPolicy policy = new SystemDestinationPolicy(host -> {
            switch (host) {
                case "lbx-ollama":
                    return new InetAddress[] {InetAddress.getByName("172.20.0.9")};
                case "loopback.example":
                    return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
                default:
                    throw new UnknownHostException(host);
            }
        });
        service = new PluginRegistrationService(repository, projectRepository, adminAuthorizationService, policy, cipher);
        nextId = 1;
        lenient().when(repository.save(any(PluginRegistration.class))).thenAnswer(inv -> {
            PluginRegistration r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(nextId++);
            }
            return r;
        });
    }

    private PluginRegistration existing(long id, PluginKind kind, String name, String url, boolean inUse) {
        PluginRegistration r = new PluginRegistration(kind, name, url, null);
        r.setId(id);
        r.setInUse(inUse);
        return r;
    }

    @Test
    void 追加は管理者を要求し_dockerネットワーク内のlbxを保存できる() {
        RegistrationView view = service.create("LLM", " 社内のOllama ", "http://lbx-ollama:11434/v1", null);

        verify(adminAuthorizationService).requireAdmin();
        assertEquals(new RegistrationView(1L, "LLM", "社内のOllama", "http://lbx-ollama:11434/v1", false, null), view);
    }

    @Test
    void 管理者でなければ何も保存しない() {
        doThrow(new ForbiddenException("admin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> service.create("LLM", "a", "http://lbx-ollama:1", null));
        assertThrows(ForbiddenException.class, () -> service.list());
        assertThrows(ForbiddenException.class, () -> service.update(1L, "a", "http://lbx-ollama:1", null));
        assertThrows(ForbiddenException.class, () -> service.delete(1L));
        assertThrows(ForbiddenException.class, () -> service.activate(1L));
        verifyNoInteractions(repository);
    }

    @Test
    void 禁止アドレスと名前解決できないホストとhttp以外は理由つきで保存しない() {
        for (String url : new String[] {"http://127.0.0.1:11434", "http://169.254.169.254/", "http://loopback.example",
                "http://unknown.invalid", "ftp://lbx-ollama"}) {
            assertThrows(IllegalArgumentException.class, () -> service.create("LLM", "a", url, null), url);
        }
        verify(repository, never()).save(any());
    }

    @Test
    void 種類が不明_名前が空_名前が長すぎるは拒否する() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.create("BOGUS", "a", "http://lbx-ollama:1", null)).getMessage().contains("種類"));
        assertThrows(IllegalArgumentException.class, () -> service.create(null, "a", "http://lbx-ollama:1", null));
        assertThrows(IllegalArgumentException.class, () -> service.create("LLM", " ", "http://lbx-ollama:1", null));
        assertThrows(IllegalArgumentException.class, () -> service.create("LLM", null, "http://lbx-ollama:1", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.create("LLM", "あ".repeat(101), "http://lbx-ollama:1", null));
        assertDoesNotThrowCreate("あ".repeat(100));
    }

    private void assertDoesNotThrowCreate(String name) {
        service.create("LLM", name, "http://lbx-ollama:1", null);
    }

    @Test
    void 図のレンダリングとPenpotの2件目は理由つきで拒否し_1件目は保存できる() {
        when(repository.countByKind(PluginKind.DIAGRAM_RENDERER)).thenReturn(1L);
        when(repository.countByKind(PluginKind.PENPOT)).thenReturn(1L);

        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> service.create("DIAGRAM_RENDERER", "b", "http://lbx-ollama:1", null));
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> service.create("PENPOT", "b", "http://lbx-ollama:1", null));
        assertTrue(e1.getMessage().contains("1件まで"), e1.getMessage());
        assertTrue(e2.getMessage().contains("1件まで"), e2.getMessage());
        // LLM・画像生成は複数登録できる
        service.create("LLM", "6件目", "http://lbx-ollama:1", null);
    }

    @Test
    void drawioUrlは図のレンダリングだけが持て_形式だけを検査する() {
        when(repository.countByKind(PluginKind.DIAGRAM_RENDERER)).thenReturn(0L);

        // 名前解決できないホストでも形式が正しければ保存できる(サーバーは接続しない)
        RegistrationView view = service.create(
                "DIAGRAM_RENDERER", "図", "http://lbx-ollama:8085", "https://drawio.example.com/");
        assertEquals("https://drawio.example.com/", view.drawioUrl());
        assertThrows(IllegalArgumentException.class,
                () -> service.create("DIAGRAM_RENDERER", "図", "http://lbx-ollama:8085", "ftp://x"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.create("LLM", "a", "http://lbx-ollama:1", "https://drawio.example.com/"))
                .getMessage().contains("drawioUrl"));
        // 空文字は未指定として扱う
        assertNull(service.create("DIAGRAM_RENDERER", "図", "http://lbx-ollama:8085", "  ").drawioUrl());
    }

    @Test
    void 一覧は種類順に全件を返す() {
        when(repository.findAllByOrderByKindAscIdAsc()).thenReturn(List.of(
                existing(1, PluginKind.LLM, "a", "http://lbx-ollama:1", true),
                existing(2, PluginKind.PENPOT, "p", "http://lbx-ollama:2", false)));

        List<RegistrationView> views = service.list();

        assertEquals(2, views.size());
        assertTrue(views.get(0).inUse());
        assertEquals("PENPOT", views.get(1).kind());
    }

    @Test
    void 編集は名前とURLを検査して更新し_種類は変えない() {
        PluginRegistration r = existing(3, PluginKind.LLM, "旧", "http://lbx-ollama:1", true);
        when(repository.findById(3L)).thenReturn(Optional.of(r));

        RegistrationView view = service.update(3L, "新", "http://lbx-ollama:2", null);

        assertEquals("新", view.name());
        assertEquals("http://lbx-ollama:2", view.url());
        assertEquals("LLM", view.kind());
        assertTrue(view.inUse());
        assertThrows(IllegalArgumentException.class, () -> service.update(3L, "新", "http://127.0.0.1:1", null));
        assertEquals("http://lbx-ollama:2", r.getBaseUrl());
    }

    @Test
    void 図のレンダリングの編集ではdrawioUrlを更新し_他の種類では指定を拒否する() {
        PluginRegistration diagram = existing(4, PluginKind.DIAGRAM_RENDERER, "図", "http://lbx-ollama:1", false);
        PluginRegistration llm = existing(5, PluginKind.LLM, "a", "http://lbx-ollama:1", false);
        when(repository.findById(4L)).thenReturn(Optional.of(diagram));
        when(repository.findById(5L)).thenReturn(Optional.of(llm));

        assertEquals("https://d.example/", service.update(4L, "図", "http://lbx-ollama:1", "https://d.example/").drawioUrl());
        assertNull(service.update(4L, "図", "http://lbx-ollama:1", null).drawioUrl());
        assertThrows(IllegalArgumentException.class, () -> service.update(5L, "a", "http://lbx-ollama:1", "https://d.example/"));
    }

    @Test
    void 存在しないIDの編集_削除_使用中の切り替えは拒否する() {
        when(repository.findById(9L)).thenReturn(Optional.empty());

        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.update(9L, "a", "http://lbx-ollama:1", null))
                .getMessage().contains("見つかりません"));
        assertThrows(IllegalArgumentException.class, () -> service.delete(9L));
        assertThrows(IllegalArgumentException.class, () -> service.activate(9L));
    }

    @Test
    void 削除すると行が消える() {
        PluginRegistration r = existing(3, PluginKind.LLM, "a", "http://lbx-ollama:1", true);
        when(repository.findById(3L)).thenReturn(Optional.of(r));

        service.delete(3L);

        verify(repository).delete(r);
    }

    @Test
    void 使用中の切り替えは同じ種類の他の登録を外し_1件だけにする() {
        PluginRegistration a = existing(1, PluginKind.LLM, "a", "http://lbx-ollama:1", true);
        PluginRegistration b = existing(2, PluginKind.LLM, "b", "http://lbx-ollama:2", false);
        when(repository.findById(2L)).thenReturn(Optional.of(b));
        when(repository.findByKindOrderByIdAsc(PluginKind.LLM)).thenReturn(new ArrayList<>(List.of(a, b)));

        RegistrationView view = service.activate(2L);

        assertTrue(view.inUse());
        assertFalse(a.isInUse());
        assertTrue(b.isInUse());
    }

    @Test
    void 図のレンダリングとPenpotに使用中の切り替えは無い() {
        when(repository.findById(4L)).thenReturn(Optional.of(
                existing(4, PluginKind.PENPOT, "p", "http://lbx-ollama:1", false)));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.activate(4L))
                .getMessage().contains("使用中"));
    }

    @Test
    void 解決はLLMでは使用中の登録を返し_管理者を要求しない() {
        PluginRegistration r = existing(7, PluginKind.LLM, "社内", "http://lbx-ollama:11434/v1", true);
        when(repository.findFirstByKindAndInUseTrue(PluginKind.LLM)).thenReturn(Optional.of(r));

        ResolvedRegistration resolved = service.resolve("LLM", null);

        assertEquals(new ResolvedRegistration(true, 7L, "社内", "http://lbx-ollama:11434/v1", "SYSTEM", null, null), resolved);
        verifyNoInteractions(adminAuthorizationService);
    }

    @Test
    void 使用中が無い_登録が無い種類は未接続を返し_環境変数の値は返さない() {
        when(repository.findFirstByKindAndInUseTrue(PluginKind.IMAGE_GENERATION)).thenReturn(Optional.empty());
        when(repository.findByKindOrderByIdAsc(PluginKind.PENPOT)).thenReturn(List.of());

        ResolvedRegistration image = service.resolve("IMAGE_GENERATION", null);
        ResolvedRegistration penpot = service.resolve("PENPOT", null);

        assertEquals(new ResolvedRegistration(false, null, null, null, null, null, null), image);
        assertEquals(image, penpot);
    }

    @Test
    void 図のレンダリングとPenpotの解決は唯一の登録を返し_図はdrawioUrlも返す() {
        PluginRegistration d = existing(8, PluginKind.DIAGRAM_RENDERER, "図", "http://lbx-ollama:8085", false);
        d.setDrawioUrl("https://drawio.example/");
        when(repository.findByKindOrderByIdAsc(PluginKind.DIAGRAM_RENDERER)).thenReturn(List.of(d));

        ResolvedRegistration resolved = service.resolve("DIAGRAM_RENDERER", null);

        assertTrue(resolved.connected());
        assertEquals("https://drawio.example/", resolved.drawioUrl());
        assertEquals("SYSTEM", resolved.scope());
    }

    @Test
    void 解決の種類が不明なら拒否する() {
        assertThrows(IllegalArgumentException.class, () -> service.resolve("BOGUS", null));
        assertThrows(IllegalArgumentException.class, () -> service.resolve(null, null));
    }

    // ---- プロジェクト単位の上書き(issue #1808): プロジェクト → システム全体 → 未接続 ----

    private static final long PROJECT = 10L;

    private ProjectPluginRegistration projectRegistration(long id, PluginKind kind, String name, boolean inUse, String credential) {
        ProjectPluginRegistration r = new ProjectPluginRegistration(PROJECT, kind, name, "http://gpu-pc.example:" + id, null);
        r.setId(id);
        r.setInUse(inUse);
        if (credential != null) {
            r.setCredentialEncrypted(cipher.encrypt(credential));
        }
        return r;
    }

    @Test
    void プロジェクトに上書きがあれば_上書き側の使用中だけを返し_システム全体は見ない_認証情報は復号して返す() {
        when(projectRepository.findByProjectIdAndKindOrderByIdAsc(PROJECT, PluginKind.LLM)).thenReturn(List.of(
                projectRegistration(1, PluginKind.LLM, "予備", false, null),
                projectRegistration(2, PluginKind.LLM, "本命", true, "sk-secret")));

        ResolvedRegistration resolved = service.resolve("LLM", PROJECT);

        assertEquals(new ResolvedRegistration(true, 2L, "本命", "http://gpu-pc.example:2", "PROJECT", null, "sk-secret"), resolved);
        verifyNoInteractions(repository);
    }

    @Test
    void 上書きの認証情報が無ければnullを返す() {
        when(projectRepository.findByProjectIdAndKindOrderByIdAsc(PROJECT, PluginKind.IMAGE_GENERATION))
                .thenReturn(List.of(projectRegistration(3, PluginKind.IMAGE_GENERATION, "画像", true, null)));

        ResolvedRegistration resolved = service.resolve("IMAGE_GENERATION", PROJECT);

        assertEquals("PROJECT", resolved.scope());
        assertNull(resolved.credential());
    }

    @Test
    void 上書きがあっても使用中が無ければシステム全体へ混ぜず_未接続を返す() {
        when(projectRepository.findByProjectIdAndKindOrderByIdAsc(PROJECT, PluginKind.LLM))
                .thenReturn(List.of(projectRegistration(1, PluginKind.LLM, "予備", false, "k")));

        ResolvedRegistration resolved = service.resolve("LLM", PROJECT);

        assertFalse(resolved.connected());
        assertNull(resolved.credential());
        verifyNoInteractions(repository);
    }

    @Test
    void 図のレンダリングの上書きは唯一の登録を返す() {
        ProjectPluginRegistration d = projectRegistration(4, PluginKind.DIAGRAM_RENDERER, "図", false, null);
        d.setDrawioUrl("https://drawio.example/");
        when(projectRepository.findByProjectIdAndKindOrderByIdAsc(PROJECT, PluginKind.DIAGRAM_RENDERER)).thenReturn(List.of(d));

        ResolvedRegistration resolved = service.resolve("DIAGRAM_RENDERER", PROJECT);

        assertTrue(resolved.connected());
        assertEquals("PROJECT", resolved.scope());
        assertEquals("https://drawio.example/", resolved.drawioUrl());
    }

    @Test
    void プロジェクトに上書きが無い種類はシステム全体の使用中を返し_認証情報は返さない() {
        when(projectRepository.findByProjectIdAndKindOrderByIdAsc(PROJECT, PluginKind.LLM)).thenReturn(List.of());
        PluginRegistration r = existing(7, PluginKind.LLM, "社内", "http://lbx-ollama:11434/v1", true);
        when(repository.findFirstByKindAndInUseTrue(PluginKind.LLM)).thenReturn(Optional.of(r));

        ResolvedRegistration resolved = service.resolve("LLM", PROJECT);

        assertEquals(new ResolvedRegistration(true, 7L, "社内", "http://lbx-ollama:11434/v1", "SYSTEM", null, null), resolved);
    }

    @Test
    void どちらにも無ければ未接続を返す() {
        when(projectRepository.findByProjectIdAndKindOrderByIdAsc(PROJECT, PluginKind.LLM)).thenReturn(List.of());
        when(repository.findFirstByKindAndInUseTrue(PluginKind.LLM)).thenReturn(Optional.empty());

        assertFalse(service.resolve("LLM", PROJECT).connected());
    }

    @Test
    void Penpotはプロジェクトを指定してもシステム全体だけを見る() {
        when(repository.findByKindOrderByIdAsc(PluginKind.PENPOT))
                .thenReturn(List.of(existing(9, PluginKind.PENPOT, "p", "http://lbx-ollama:3449", false)));

        ResolvedRegistration resolved = service.resolve("PENPOT", PROJECT);

        assertEquals("SYSTEM", resolved.scope());
        verifyNoInteractions(projectRepository);
    }

    @Test
    void 解決の結果を文字列にしても認証情報の値は出ない() {
        ResolvedRegistration withKey = new ResolvedRegistration(true, 2L, "本命", "http://gpu-pc:1", "PROJECT", null, "sk-secret");
        ResolvedRegistration withoutKey = new ResolvedRegistration(true, 7L, "社内", "http://lbx-ollama:1", "SYSTEM", null, null);

        assertFalse(withKey.toString().contains("sk-secret"));
        assertTrue(withKey.toString().contains("credential=****"));
        assertTrue(withoutKey.toString().contains("credential=null"));
    }
}
