package com.letsblog.ai.service;

import com.letsblog.ai.domain.ProjectAiSettings;
import com.letsblog.ai.repository.ProjectAiSettingsRepository;
import com.letsblog.common.net.DestinationAddressRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * プロジェクト単位のAI(LLM)関連設定(project_ai_settings)の読み書きを扱う(issue #571)。
 * projects god-tableの分割で切り出された設定テーブルで、行は初回書き込み時に遅延作成する
 * (未設定のプロジェクトに空行を作らないため)。
 */
@Service
public class ProjectAiSettingsService {

    /** platform-serviceのAppSettingServiceと同じ規約(空白・制御文字を含まない)。 */
    private static final Pattern WHITESPACE_OR_CONTROL = Pattern.compile(".*[\\s\\p{Cntrl}].*", Pattern.DOTALL);

    /**
     * 拒否するホスト名(小文字、末尾のドットなし。issue #1518)。クラウドメタデータと、docker-compose.ymlの
     * サービス名・container_nameのうちOllama / ComfyUI(ollama / comfyui / comfyui-cpu とその lbs- 付き)以外。
     * docker-compose.ymlとの食い違いはProjectConnectionUrlDenylistTestが検出する。
     */
    private static final Set<String> DENIED_HOSTS = Set.of(
            "metadata.google.internal",
            "localhost",
            "reverse-proxy", "web", "media", "ai", "content", "analytics", "platform", "project", "publishing",
            "log-writer", "gateway", "identity", "rabbitmq", "docker-socket-proxy", "mysql", "phpmyadmin",
            "keycloak-postgres", "keycloak", "penpot-frontend", "penpot-backend", "penpot-mcp",
            "penpot-exporter", "penpot-postgres", "penpot-valkey", "penpot-mailcatch", "ollama-model-init",
            "plantuml", "drawio", "wordpress",
            "lbs-reverse-proxy", "lbs-web", "lbs-media", "lbs-ai", "lbs-content", "lbs-analytics",
            "lbs-platform", "lbs-project", "lbs-publishing", "lbs-log-writer", "lbs-gateway", "lbs-identity",
            "lbs-rabbitmq", "lbs-docker-socket-proxy", "lbs-mysql", "lbs-phpmyadmin", "lbs-keycloak-postgres",
            "lbs-keycloak", "lbs-penpot-frontend", "lbs-penpot-backend", "lbs-penpot-mcp",
            "lbs-penpot-exporter", "lbs-penpot-postgres", "lbs-penpot-valkey", "lbs-penpot-mailcatch",
            "lbs-ollama-model-init", "lbs-plantuml", "lbs-drawio", "lbs-wordpress");

    /** 10進・16進(0x)・8進(先頭0)のいずれかの数値表記(IPv4の1要素)。 */
    private static final Pattern IPV4_PART = Pattern.compile("0[xX][0-9a-fA-F]+|[0-9]+");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9a-fA-F:.]+");

    private final ProjectAiSettingsRepository repository;

    public ProjectAiSettingsService(ProjectAiSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<ProjectAiSettings> findByProjectId(Long projectId) {
        return repository.findByProjectId(projectId);
    }

    @Transactional
    public ProjectAiSettings getOrCreate(Long projectId) {
        return repository.findByProjectId(projectId)
                .orElseGet(() -> repository.save(new ProjectAiSettings(projectId)));
    }

    @Transactional(readOnly = true)
    public String getLlmModel(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::getLlmModel).orElse(null);
    }

    @Transactional
    public void setLlmModel(Long projectId, String llmModel) {
        ProjectAiSettings settings = getOrCreate(projectId);
        settings.setLlmModel(llmModel);
        repository.save(settings);
    }

    @Transactional(readOnly = true)
    public String getLlmProvider(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::getLlmProvider).orElse(null);
    }

    @Transactional
    public void setLlmProvider(Long projectId, String llmProvider) {
        ProjectAiSettings settings = getOrCreate(projectId);
        settings.setLlmProvider(llmProvider);
        repository.save(settings);
    }

    @Transactional(readOnly = true)
    public boolean hasBraveSearchApiKey(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::hasBraveSearchApiKey).orElse(false);
    }

    @Transactional(readOnly = true)
    public byte[] getBraveSearchApiKeyEncrypted(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::getBraveSearchApiKeyEncrypted).orElse(null);
    }

    @Transactional
    public void setBraveSearchApiKeyEncrypted(Long projectId, byte[] braveSearchApiKeyEncrypted) {
        ProjectAiSettings settings = getOrCreate(projectId);
        settings.setBraveSearchApiKeyEncrypted(braveSearchApiKeyEncrypted);
        repository.save(settings);
    }

    @Transactional(readOnly = true)
    public boolean hasOpenAiApiKey(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::hasOpenAiApiKey).orElse(false);
    }

    @Transactional(readOnly = true)
    public byte[] getOpenAiApiKeyEncrypted(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::getOpenAiApiKeyEncrypted).orElse(null);
    }

    /** プロジェクト単位のChatGPT(OpenAI)APIキー(暗号化済み)を保存する。nullで削除(issue #1506)。 */
    @Transactional
    public void setOpenAiApiKeyEncrypted(Long projectId, byte[] openAiApiKeyEncrypted) {
        ProjectAiSettings settings = getOrCreate(projectId);
        settings.setOpenAiApiKeyEncrypted(openAiApiKeyEncrypted);
        repository.save(settings);
    }

    @Transactional(readOnly = true)
    public boolean hasClaudeApiKey(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::hasClaudeApiKey).orElse(false);
    }

    @Transactional(readOnly = true)
    public byte[] getClaudeApiKeyEncrypted(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::getClaudeApiKeyEncrypted).orElse(null);
    }

    /** プロジェクト単位のClaude(Anthropic)APIキー(暗号化済み)を保存する。nullで削除(issue #1507)。 */
    @Transactional
    public void setClaudeApiKeyEncrypted(Long projectId, byte[] claudeApiKeyEncrypted) {
        ProjectAiSettings settings = getOrCreate(projectId);
        settings.setClaudeApiKeyEncrypted(claudeApiKeyEncrypted);
        repository.save(settings);
    }

    @Transactional(readOnly = true)
    public String getOllamaBaseUrl(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::getOllamaBaseUrl).orElse(null);
    }

    @Transactional(readOnly = true)
    public String getComfyuiBaseUrl(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::getComfyuiBaseUrl).orElse(null);
    }

    /**
     * Ollama / ComfyUI接続先の上書きを保存する(issue #1503)。引数がnullの項目は変更せず、
     * 空文字(空白のみ含む)の項目は上書きを解除する。値はhttp://またはhttps://で始まり、
     * 空白・制御文字を含まないこと。いずれかが不正なら何も保存せず{@link InvalidConnectionUrlException}を投げる。
     */
    @Transactional
    public void setConnectionUrls(Long projectId, String ollamaBaseUrl, String comfyuiBaseUrl) {
        String ollama = normalizeUrl("ollamaBaseUrl", ollamaBaseUrl);
        String comfyui = normalizeUrl("comfyuiBaseUrl", comfyuiBaseUrl);
        ProjectAiSettings settings = getOrCreate(projectId);
        if (ollamaBaseUrl != null) {
            settings.setOllamaBaseUrl(ollama);
        }
        if (comfyuiBaseUrl != null) {
            settings.setComfyuiBaseUrl(comfyui);
        }
        repository.save(settings);
    }

    /** null(変更しない)とnull(解除)を混同しないよう、呼び出し側は元の引数のnullで区別する。 */
    private static String normalizeUrl(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            throw new InvalidConnectionUrlException(field + " はhttp://またはhttps://から始まるURLを指定してください");
        }
        if (WHITESPACE_OR_CONTROL.matcher(value).matches()) {
            throw new InvalidConnectionUrlException(field + " に空白文字・制御文字は使用できません");
        }
        String host = extractHost(value);
        if (host == null) {
            throw new InvalidConnectionUrlException(field + " のホストを解釈できません");
        }
        if (isDeniedHost(host)) {
            throw new InvalidConnectionUrlException(field + " に指定できない接続先です(メタデータ・loopback・内部サービス)");
        }
        return value;
    }

    /**
     * URLからホスト(小文字、末尾のドットなし、IPv6は角括弧なし)を取り出す。取り出せなければnull。
     * java.net.URIは「256.1.1.1」のような表記でhostをnullにするため使わず、authorityを自前で分解する。
     */
    private static String extractHost(String url) {
        String rest = url.substring(url.indexOf("://") + 3);
        int end = rest.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int i = rest.indexOf(c);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        String authority = rest.substring(0, end);
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        String host;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = authority.substring(1, close);
        } else {
            int colon = authority.indexOf(':');
            host = colon >= 0 ? authority.substring(0, colon) : authority;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".") && !host.contains(":")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.isEmpty() ? null : host;
    }

    /** ホスト名は文字列だけで判定し、DNS解決はしない。IPリテラルと判定できた場合のみアドレスとして解釈する。 */
    private static boolean isDeniedHost(String host) {
        if (host.contains(":")) {
            return isDeniedAddress(parseIpv6Literal(host));
        }
        byte[] ipv4 = parseLegacyIpv4(host);
        if (ipv4 != null) {
            return isDeniedAddress(ipv4);
        }
        return DENIED_HOSTS.contains(host) || host.endsWith(".localhost");
    }

    /** IPv6リテラルをバイト列にする。解釈できなければ拒否側に倒すため全0(未指定アドレス)を返す。 */
    private static byte[] parseIpv6Literal(String host) {
        if (!IPV6_LITERAL.matcher(host).matches()) {
            return new byte[16];
        }
        try {
            // ':' を含み16進・'.'だけの文字列はリテラルとして解釈され、DNS解決は走らない。
            // IPv4射影(::ffff:a.b.c.d)はInet4Addressになる。
            return InetAddress.getByName(host).getAddress();
        } catch (UnknownHostException e) {
            return new byte[16];
        }
    }

    /**
     * 10進(2130706433)・16進(0x7f000001)・8進(0177.0.0.1)・省略形(127.1)のIPv4表記を解釈する。
     * 数値表記として成立しなければ(ホスト名とみなすため)nullを返す。
     */
    private static byte[] parseLegacyIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length > 4) {
            return null;
        }
        long[] values = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Long value = parseIpv4Part(parts[i]);
            if (value == null) {
                return null;
            }
            values[i] = value;
        }
        long last = values[values.length - 1];
        long limit = 1L << (8 * (5 - values.length));
        if (last >= limit) {
            return null;
        }
        long address = last;
        for (int i = 0; i < values.length - 1; i++) {
            if (values[i] > 255) {
                return null;
            }
            address |= values[i] << (8 * (3 - i));
        }
        return new byte[] {(byte) (address >> 24), (byte) (address >> 16), (byte) (address >> 8), (byte) address};
    }

    private static Long parseIpv4Part(String part) {
        if (!IPV4_PART.matcher(part).matches()) {
            return null;
        }
        try {
            if (part.regionMatches(true, 0, "0x", 0, 2)) {
                return Long.parseLong(part.substring(2), 16);
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return Long.parseLong(part, 8);
            }
            return Long.parseLong(part);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 4バイト(IPv4)または16バイト(IPv6)のアドレスが拒否対象か。接続時検査(issue #1547)と共通の規則。 */
    private static boolean isDeniedAddress(byte[] a) {
        return DestinationAddressRules.isDeniedAddress(a);
    }
}
