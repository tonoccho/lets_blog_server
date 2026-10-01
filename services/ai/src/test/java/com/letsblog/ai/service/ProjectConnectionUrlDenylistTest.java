package com.letsblog.ai.service;

import com.letsblog.ai.domain.ProjectAiSettings;
import com.letsblog.ai.repository.ProjectAiSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * プロジェクト単位の接続先URLの宛先制限(拒否リスト、issue #1518)のテスト。
 * 判定はURL文字列だけで行い、ホスト名のDNS解決はしない。
 */
@ExtendWith(MockitoExtension.class)
class ProjectConnectionUrlDenylistTest {

    private static final Set<String> ALLOWED_STACK_NAMES = Set.of(
            "ollama", "comfyui", "comfyui-cpu", "lbs-ollama", "lbs-comfyui", "lbs-comfyui-cpu");

    @Mock
    private ProjectAiSettingsRepository repository;

    private ProjectAiSettingsService service() {
        lenient().when(repository.findByProjectId(1L)).thenReturn(java.util.Optional.empty());
        lenient().when(repository.save(any(ProjectAiSettings.class))).thenAnswer(inv -> inv.getArgument(0));
        return new ProjectAiSettingsService(repository);
    }

    private void assertRejected(String url) {
        ProjectAiSettingsService service = service();
        assertThrows(InvalidConnectionUrlException.class, () -> service.setConnectionUrls(1L, url, null), "ollama: " + url);
        assertThrows(InvalidConnectionUrlException.class, () -> service.setConnectionUrls(1L, null, url), "comfyui: " + url);
        verify(repository, never()).save(any());
    }

    private void assertAccepted(String url) {
        ProjectAiSettingsService service = service();
        service.setConnectionUrls(1L, url, url);
    }

    // ---- AC1: メタデータ・リンクローカル ----

    @Test
    void クラウドメタデータとリンクローカルは拒否する() {
        for (String url : List.of(
                "http://169.254.169.254/latest/meta-data/",
                "http://169.254.0.1:11434",
                "http://169.254.255.255/",
                "http://metadata.google.internal/",
                "http://METADATA.GOOGLE.INTERNAL./",
                "http://[fe80::1]:11434",
                "http://[febf::1]/",
                "http://[fd00:ec2::254]/latest/")) {
            assertRejected(url);
        }
    }

    // ---- AC2: loopback・未指定 ----

    @Test
    void loopbackと未指定アドレスは拒否する() {
        for (String url : List.of(
                "http://127.0.0.1:11434",
                "http://127.255.255.254/",
                "http://localhost:8188",
                "http://LOCALHOST:8188",
                "http://localhost.:8188",
                "http://foo.localhost:8188",
                "http://[::1]:11434",
                "http://0.0.0.0:11434",
                "http://[::]:11434")) {
            assertRejected(url);
        }
    }

    // ---- AC3: 内部サービス名 ----

    @Test
    void 内部サービス名は拒否する() {
        for (String url : List.of(
                "http://mysql:3306",
                "http://docker-socket-proxy:2375",
                "http://lbs-keycloak:8080",
                "http://lbs-mysql:3306",
                "http://RabbitMQ:5672",
                "http://gateway:8080",
                "https://keycloak.:8443/")) {
            assertRejected(url);
        }
    }

    // ---- AC4: 許可 ----

    @Test
    void LANとOllama_ComfyUIのサービス名は許可する() {
        for (String url : List.of(
                "http://192.168.1.50:11434/v1",
                "http://10.0.0.5:11434",
                "http://172.16.0.9:8188",
                "http://gpu-box.lan:11434",
                "http://gpu:11434/v1",
                "https://comfy.example:8188",
                "http://ollama:11434/v1",
                "http://comfyui:8188",
                "http://comfyui-cpu:8188",
                "http://lbs-ollama:11434",
                "http://lbs-comfyui:8188",
                "http://lbs-comfyui-cpu:8188",
                "http://[2001:db8::1]:11434",
                "http://8.8.8.8:11434",
                "http://169.253.1.1/",
                "http://[::2]/")) {
            assertAccepted(url);
        }
    }

    // ---- AC5: 別表記 ----

    @Test
    void 別表記のIPリテラルも正規化して拒否する() {
        for (String url : List.of(
                "http://2130706433:11434",
                "http://0x7f000001:11434",
                "http://0X7F000001:11434",
                "http://127.1:11434",
                "http://127.0.1:11434",
                "http://0177.0.0.1:11434",
                "http://0x7f.0.0.1:11434",
                "http://0:11434",
                "http://2852039166/",
                "http://0xa9fea9fe/",
                "http://169.254.43518/",
                "http://[::ffff:127.0.0.1]:11434",
                "http://[::ffff:7f00:1]:11434",
                "http://[::ffff:169.254.169.254]/",
                "http://[::ffff:0.0.0.0]/",
                "http://[0:0:0:0:0:0:0:1]/")) {
            assertRejected(url);
        }
    }

    @Test
    void 別表記でも許可対象のアドレスは通す() {
        assertAccepted("http://3232235826:11434");      // 192.168.1.50
        assertAccepted("http://[::ffff:192.168.1.50]:11434");
        assertAccepted("http://192.168.1:11434");      // 192.168.0.1
    }

    @Test
    void 数字だけに見えても不正なIPv4表記はIPリテラルとして扱わずホスト名として通す() {
        // 範囲外・不正な数値表記はホスト名扱い(DNS解決はしない)。拒否リストの名前でなければ保存できる。
        assertAccepted("http://256.1.1.1:11434");
        assertAccepted("http://0xzz:11434");
        assertAccepted("http://1.2.3.4.5:11434");
        assertAccepted("http://99999999999:11434");
        assertAccepted("http://08.1:11434");
    }

    @Test
    void ホストを取り出せないURLは拒否する() {
        assertRejected("http://");
        assertRejected("http:///path");
        assertRejected("http://[::1:11434");
        assertRejected("http://user@:11434");
    }

    @Test
    void userinfoで拒否対象ホストを隠しても拒否する() {
        assertRejected("http://ollama@127.0.0.1:11434");
        assertRejected("http://user:pw@mysql:3306/");
        assertAccepted("http://user:pw@gpu:11434/");
    }

    @Test
    void IPv6として解釈できないリテラルは拒否側に倒す() {
        assertRejected("http://[zz::1]:11434");
        assertRejected("http://[::1%25eth0]:11434");
        assertRejected("http://[::1::2]:11434");
        assertRejected("http://[fe80::1.]:11434");
    }

    @Test
    void 拒否対象に隣接するアドレスは許可する() {
        for (String url : List.of(
                "http://169.255.0.1/", "http://128.0.0.1/", "http://126.255.255.255/",
                "http://0.0.0.1/", "http://0.0.1.0/", "http://0.1.0.0/", "http://1.0.0.0/",
                "http://[fec0::1]/", "http://[ff80::1]/", "http://[::3]/", "http://[1::1]/",
                "http://[fd00:0fc2::254]/", "http://[fd00:ec2::255]/", "http://[fd00:ec2::154]/", "http://[fd00:ec2:1::254]/",
                "http://[fd01:ec2::254]/", "http://[fd00:ec3::254]/", "http://[fe00::1]/", "http://[fd00:ec2::1:254]/",
                "http://[fd00:ec2:0:0:0:0:3:254]/", "http://[fd00:0ec2::]/")) {
            assertAccepted(url);
        }
    }

    @Test
    void authorityの区切りはスラッシュ_クエリ_フラグメントのいずれでも切る() {
        assertRejected("http://127.0.0.1?x=1");
        assertRejected("http://127.0.0.1#frag");
        assertRejected("http://mysql/gpu");
        assertAccepted("http://gpu?next=@127.0.0.1");
        assertAccepted("http://gpu#@mysql");
        assertAccepted("http://gpu/@mysql");
        assertAccepted("http://gpu?a=/b#c");
    }

    @Test
    void 末尾のドットは正規化して判定する() {
        assertRejected("http://mysql.:3306");
        assertRejected("http://127.0.0.1.:11434");
        assertAccepted("http://gpu.:11434");
    }

    @Test
    void 拒否はURL文字列だけで判定しDNS解決しない() {
        // 解決不能な名前でも、拒否リスト外なら保存できる(DNSに依存しない)。
        assertAccepted("http://at-1518-no-such-host.invalid:11434/v1");
    }

    @Test
    void 空文字と省略の扱いは変えない() {
        ProjectAiSettingsService service = service();
        service.setConnectionUrls(1L, "", null);
        service.setConnectionUrls(1L, null, "  ");
    }

    @Test
    void 片方が拒否対象なら他方も保存しない() {
        ProjectAiSettingsService service = service();
        assertThrows(InvalidConnectionUrlException.class,
                () -> service.setConnectionUrls(1L, "http://gpu:11434", "http://169.254.169.254/"));
        verify(repository, never()).save(any());
    }

    // ---- docker-compose.yml との突き合わせ ----

    @Test
    void docker_composeのOllama_ComfyUI以外のサービス名とコンテナ名はすべて拒否される() throws IOException {
        Set<String> names = composeNames();
        assertTrue(names.contains("mysql") && names.contains("lbs-mysql") && names.contains("ollama"),
                "docker-compose.yml の名前を読み取れていない: " + names);

        Set<String> missing = new TreeSet<>();
        for (String name : names) {
            if (ALLOWED_STACK_NAMES.contains(name)) {
                continue;
            }
            try {
                service().setConnectionUrls(1L, "http://" + name + ":8080", null);
                missing.add(name);
            } catch (InvalidConnectionUrlException expected) {
                // 拒否されていればよい
            }
        }
        assertEquals(Set.of(), missing, "拒否リストに無い docker-compose.yml の名前(サービス追加時はProjectAiSettingsServiceへ追加する)");
    }

    @Test
    void docker_composeのOllama_ComfyUI名は許可される() throws IOException {
        Set<String> names = composeNames();
        for (String name : ALLOWED_STACK_NAMES) {
            assertTrue(names.contains(name), "docker-compose.yml に無い許可名: " + name);
            assertAccepted("http://" + name + ":8080");
        }
        assertFalse(names.isEmpty());
    }

    private static Set<String> composeNames() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        Path compose = null;
        while (dir != null) {
            Path candidate = dir.resolve("docker-compose.yml");
            if (Files.exists(candidate)) {
                compose = candidate;
                break;
            }
            dir = dir.getParent();
        }
        if (compose == null) {
            throw new IOException("docker-compose.yml が見つからない");
        }
        Set<String> names = new TreeSet<>();
        boolean inServices = false;
        Pattern service = Pattern.compile("^  ([a-z0-9][a-z0-9_-]*):\\s*$");
        Pattern container = Pattern.compile("^\\s+container_name:\\s*([A-Za-z0-9_.-]+)\\s*$");
        for (String line : Files.readAllLines(compose)) {
            if (line.matches("^[a-z][a-z-]*:.*")) {
                inServices = line.startsWith("services:");
                continue;
            }
            if (!inServices) {
                continue;
            }
            Matcher s = service.matcher(line);
            if (s.matches()) {
                names.add(s.group(1));
            }
            Matcher c = container.matcher(line);
            if (c.matches()) {
                names.add(c.group(1));
            }
        }
        return names;
    }
}
