package com.letsblog.api.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.api.config.LegacyJacksonRestClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * PenpotのRPC API(/api/rpc/command/*)を呼び出す薄いクライアント。
 * Penpotにはテキストプロンプトからデザインを自動生成するAPIが存在しないため、
 * 「カスタムタグ生成時にOllamaへ送ったプロンプトと生成結果を元に、Penpot上へ空のデザインファイルを
 * 作成しコメントスレッドとして書き込む」ハンドオフ方式で連携する。以降はPenpot上でデザイナーが
 * 続きを視覚的に編集できる。
 *
 * PenpotのRPC認証はセッションCookie(auth-token)方式のため、専用サービスアカウントで
 * ログインし、取得したCookieをインスタンス内にキャッシュして使い回す。アカウント未作成の場合は
 * 初回呼び出し時に自動登録する(PENPOT_FLAGSでメール確認が無効化されている前提)。
 *
 * RPC呼び出し自体はDockerネットワーク内部URL(app.penpot-base-url、例: http://penpot-frontend:8080)
 * を使うが、ユーザーがブラウザで開くリンクはホストからアクセス可能な公開URL(app.penpot-public-url、
 * 例: http://localhost:9001)で組み立てる必要があるため、この2つを明確に分離している。
 *
 * 作成したファイルはサービスアカウント自身のチーム配下に存在するため、ワークスペースURL(/#/workspace/...)
 * をそのまま渡すと、開く側がサービスアカウントと同じPenpotチームに所属していない限り404相当のアクセス拒否
 * となる(issue #348)。そのため誰でも開けるshare-link(/api/rpc/command/create-share-link)を発行し、
 * 閲覧用URL(/#/view/...?share-id=...)を組み立てる。
 */
@Component
public class PenpotClient {

    private static final Logger log = LoggerFactory.getLogger(PenpotClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String ROOT_FRAME_ID = "00000000-0000-0000-0000-000000000000";
    /** Penpot側のcreate-comment-threadは content を最大750文字までしか受け付けないため、安全側で切り詰める。 */
    private static final int MAX_COMMENT_LENGTH = 700;

    private final RestClient client;
    private final String publicBaseUrl;
    private final String serviceEmail;
    private final String servicePassword;

    private volatile Session session;

    @Autowired
    public PenpotClient(
            @Value("${app.penpot-base-url}") String baseUrl,
            @Value("${app.penpot-public-url}") String publicBaseUrl,
            @Value("${app.penpot-service-email}") String serviceEmail,
            @Value("${app.penpot-service-password}") String servicePassword) {
        this(builderWithTimeout(baseUrl), publicBaseUrl, serviceEmail, servicePassword);
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderを直接受け取るコンストラクタ。 */
    PenpotClient(RestClient.Builder builder, String publicBaseUrl, String serviceEmail, String servicePassword) {
        LegacyJacksonRestClientConfig.preferJackson2(builder);
        builder.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        this.client = builder.build();
        this.publicBaseUrl = publicBaseUrl;
        this.serviceEmail = serviceEmail;
        this.servicePassword = servicePassword;
    }

    private static RestClient.Builder builderWithTimeout(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(TIMEOUT);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory);
    }

    public record DesignFile(String fileId, String projectId, String url) {
    }

    private record Session(String authCookie, String defaultProjectId) {
    }

    /**
     * 指定名のPenpotデザインファイルをサービスアカウントのデフォルトプロジェクト配下に作成し、
     * promptContext(Ollamaへのプロンプトと生成結果の要約)をコメントスレッドとして書き込む。
     */
    public synchronized DesignFile createDesignFile(String fileName, String promptContext) {
        Session activeSession;
        JsonNode file;
        try {
            activeSession = ensureSession();
            file = createFile(activeSession, fileName);
        } catch (RestClientResponseException e) {
            throw new AiServiceException("Penpotデザインファイルの作成に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new AiServiceException("Penpotデザインファイルの作成中にエラーが発生しました（タイムアウトまたはネットワークエラーの可能性があります）: " + e.getMessage(), e);
        }

        String fileId = file.get("id").asText();
        String pageId = file.at("/data/pages/0").asText();

        // ファイル本体の作成は既に成功しているため、コメント付記(補足情報)の失敗でハンドオフ自体を
        // 失敗させない。コメントが書けなくてもデザイナーがPenpot上で続きを作業できることに変わりはない。
        try {
            createCommentThread(activeSession, fileId, pageId, truncate(promptContext));
        } catch (Exception e) {
            log.warn("Penpotファイル({})へのコメント作成に失敗しました(ファイル自体は作成済みのため続行します): {}", fileId, e.getMessage());
        }

        String shareId;
        try {
            shareId = createShareLink(activeSession, fileId, pageId);
        } catch (RestClientResponseException e) {
            throw new AiServiceException("Penpot共有リンクの作成に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new AiServiceException("Penpot共有リンクの作成中にエラーが発生しました（タイムアウトまたはネットワークエラーの可能性があります）: " + e.getMessage(), e);
        }

        String url = publicBaseUrl + "/#/view/" + fileId + "?page-id=" + pageId + "&share-id=" + shareId;
        return new DesignFile(fileId, activeSession.defaultProjectId(), url);
    }

    private JsonNode createFile(Session s, String fileName) {
        ObjectNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        body.put("projectId", s.defaultProjectId());
        body.put("name", fileName);
        return callAuthenticated(s, "create-file", body);
    }

    /**
     * 誰でも(サービスアカウントと同じPenpotチームに属していなくても)開けるshare-linkを発行する。
     * who-comment/who-inspectは"team"(チームメンバーのみ)/"all"(リンクを知っていれば誰でも)のいずれかで、
     * ここでは常に"all"を指定する(ハンドオフ先のデザイナーがサービスアカウントのチームに所属している保証がないため)。
     */
    private String createShareLink(Session s, String fileId, String pageId) {
        com.fasterxml.jackson.databind.node.ArrayNode pages =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        pages.add(pageId);

        ObjectNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        body.put("file-id", fileId);
        body.put("who-comment", "all");
        body.put("who-inspect", "all");
        body.set("pages", pages);
        return callAuthenticated(s, "create-share-link", body).get("id").asText();
    }

    private void createCommentThread(Session s, String fileId, String pageId, String content) {
        ObjectNode position = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        position.put("x", 0);
        position.put("y", 0);

        ObjectNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        body.put("fileId", fileId);
        body.put("pageId", pageId);
        body.put("frameId", ROOT_FRAME_ID);
        body.set("position", position);
        body.put("content", content);
        callAuthenticated(s, "create-comment-thread", body);
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > MAX_COMMENT_LENGTH ? text.substring(0, MAX_COMMENT_LENGTH) + "…" : text;
    }

    private Session ensureSession() {
        Session current = session;
        if (current != null) {
            return current;
        }
        return login(true);
    }

    /**
     * ログインを試み、サービスアカウントが未作成(認証失敗)の場合のみ自動登録してから再試行する。
     * allowRegisterはアカウント未作成による再帰を1回に限定するためのガード。
     */
    private Session login(boolean allowRegister) {
        ObjectNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        body.put("email", serviceEmail);
        body.put("password", servicePassword);
        try {
            ResponseEntity<JsonNode> response = client.post()
                    .uri("/api/rpc/command/login-with-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(JsonNode.class);
            Session newSession = buildSession(response);
            this.session = newSession;
            return newSession;
        } catch (RestClientResponseException e) {
            if (allowRegister && e.getStatusCode().is4xxClientError()) {
                registerServiceAccount();
                return login(false);
            }
            throw e;
        }
    }

    private Session buildSession(ResponseEntity<JsonNode> response) {
        String authCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .filter(cookie -> cookie.startsWith("auth-token="))
                .map(cookie -> cookie.split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new AiServiceException("Penpotログイン応答にauth-tokenが含まれていません", null));
        JsonNode profile = response.getBody();
        String defaultProjectId = profile.get("defaultProjectId").asText();
        return new Session(authCookie, defaultProjectId);
    }

    private void registerServiceAccount() {
        ObjectNode prepareBody = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        prepareBody.put("fullname", "Let's Blog Service");
        prepareBody.put("email", serviceEmail);
        prepareBody.put("password", servicePassword);

        JsonNode prepareResponse = client.post()
                .uri("/api/rpc/command/prepare-register-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .body(prepareBody)
                .retrieve()
                .body(JsonNode.class);

        ObjectNode registerBody = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        registerBody.put("token", prepareResponse.get("token").asText());
        client.post()
                .uri("/api/rpc/command/register-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .body(registerBody)
                .retrieve()
                .toBodilessEntity();
    }

    private JsonNode callAuthenticated(Session s, String command, ObjectNode body) {
        return client.post()
                .uri("/api/rpc/command/" + command)
                .header(HttpHeaders.COOKIE, s.authCookie())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }
}
