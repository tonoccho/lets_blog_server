package com.letsblog.platform.service;

import com.letsblog.platform.domain.PluginKind;
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
    private AdminAuthorizationService adminAuthorizationService;

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
        service = new PluginRegistrationService(repository, adminAuthorizationService, policy);
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

        ResolvedRegistration resolved = service.resolve("LLM");

        assertEquals(new ResolvedRegistration(true, 7L, "社内", "http://lbx-ollama:11434/v1", "SYSTEM", null), resolved);
        verifyNoInteractions(adminAuthorizationService);
    }

    @Test
    void 使用中が無い_登録が無い種類は未接続を返し_環境変数の値は返さない() {
        when(repository.findFirstByKindAndInUseTrue(PluginKind.IMAGE_GENERATION)).thenReturn(Optional.empty());
        when(repository.findByKindOrderByIdAsc(PluginKind.PENPOT)).thenReturn(List.of());

        ResolvedRegistration image = service.resolve("IMAGE_GENERATION");
        ResolvedRegistration penpot = service.resolve("PENPOT");

        assertEquals(new ResolvedRegistration(false, null, null, null, null, null), image);
        assertEquals(image, penpot);
    }

    @Test
    void 図のレンダリングとPenpotの解決は唯一の登録を返し_図はdrawioUrlも返す() {
        PluginRegistration d = existing(8, PluginKind.DIAGRAM_RENDERER, "図", "http://lbx-ollama:8085", false);
        d.setDrawioUrl("https://drawio.example/");
        when(repository.findByKindOrderByIdAsc(PluginKind.DIAGRAM_RENDERER)).thenReturn(List.of(d));

        ResolvedRegistration resolved = service.resolve("DIAGRAM_RENDERER");

        assertTrue(resolved.connected());
        assertEquals("https://drawio.example/", resolved.drawioUrl());
        assertEquals("SYSTEM", resolved.scope());
    }

    @Test
    void 解決の種類が不明なら拒否する() {
        assertThrows(IllegalArgumentException.class, () -> service.resolve("BOGUS"));
        assertThrows(IllegalArgumentException.class, () -> service.resolve(null));
    }
}
