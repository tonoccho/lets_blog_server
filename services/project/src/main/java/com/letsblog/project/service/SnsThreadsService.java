package com.letsblog.project.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.project.client.ThreadsApiClient;
import com.letsblog.project.client.ThreadsApiClient.LongLivedToken;
import com.letsblog.project.client.ThreadsApiClient.ShortLivedToken;
import com.letsblog.project.client.ThreadsApiException;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * プロジェクトの公式 Threads アカウントの接続・切断(issue #1579。Epic #1572)。X({@link SnsXService}、#1574)と同じ方式。
 *
 * <ul>
 *   <li>接続先は、プロジェクトの<b>本番サイト</b>の letsblog プラグイン。認可で得た<b>長期トークン</b>とユーザーIDを
 *       その場で {@code wp letsblog sns config set}(標準入力のJSON)でそのサイトへ送り、<b>アプリは保存しない・API で
 *       返さない</b>。アプリのシークレットと認可の途中経過は認可の間だけメモリに持つ({@link XAuthorizationStore})。</li>
 *   <li>Threads の長期トークンの更新にはアプリのシークレットが要らない(トークンだけで更新できる)ので、
 *       シークレットも短期トークンもプラグインへは送らない。更新はプラグインが期限前に行う。</li>
 *   <li>状態・履歴・テスト投稿の読み取りは SNS に依らない処理なので {@link SnsXService} に SNS 名を渡して任せる。</li>
 * </ul>
 */
@Service
public class SnsThreadsService {

    private static final String SNS = "threads";
    private static final String STATUS_CONNECTED = "接続済み";

    private final SnsXService snsXService;
    private final SiteService siteService;
    private final ThreadsApiClient threadsApiClient;
    private final XAuthorizationStore store;
    private final CurrentActorService currentActorService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public SnsThreadsService(
            SnsXService snsXService,
            SiteService siteService,
            ThreadsApiClient threadsApiClient,
            XAuthorizationStore store,
            CurrentActorService currentActorService,
            ObjectMapper objectMapper) {
        this(snsXService, siteService, threadsApiClient, store, currentActorService, objectMapper, Clock.systemUTC());
    }

    SnsThreadsService(
            SnsXService snsXService,
            SiteService siteService,
            ThreadsApiClient threadsApiClient,
            XAuthorizationStore store,
            CurrentActorService currentActorService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.snsXService = snsXService;
        this.siteService = siteService;
        this.threadsApiClient = threadsApiClient;
        this.store = store;
        this.currentActorService = currentActorService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 画面に出す Threads の接続状態と告知履歴。 */
    public XConnectionView view(Long projectId) {
        return snsXService.view(projectId, SNS);
    }

    /** 認可を始め、Threads の認可画面の URL を返す。接続できない状態では始めない。 */
    public String startAuthorization(Long projectId, String clientId, String clientSecret, String redirectUri) {
        if (isBlank(clientId) || isBlank(clientSecret) || isBlank(redirectUri)) {
            throw new IllegalArgumentException("アプリID・アプリシークレット・リダイレクト先は必須です");
        }
        Long siteId = snsXService.requireConnectableSiteId(projectId);
        String actorSub = currentActorService.getCurrentActorKeycloakSub();
        XAuthorizationStore.Pending pending =
                store.create(projectId, siteId, actorSub, clientId, clientSecret, redirectUri);
        return threadsApiClient.authorizeUrl(clientId, redirectUri, pending.state());
    }

    /**
     * Threads の認可画面から戻ったときの処理。認可コードを短期トークン → 長期トークンに交換し、本番サイトへ送る。
     * 認可を始めた本人・同じプロジェクトでなければ拒否する。
     */
    public XConnectResult completeAuthorization(Long projectId, String state, String code) {
        String invalid = "認可の有効期限が切れたか、リクエストが不正です。もう一度接続してください";
        String actorSub = currentActorService.getCurrentActorKeycloakSub();
        XAuthorizationStore.Pending pending = store.take(state)
                .filter(p -> p.projectId().equals(projectId))
                .filter(p -> p.actorSub() != null && p.actorSub().equals(actorSub))
                .orElseThrow(() -> new IllegalArgumentException(invalid));
        Long siteId = snsXService.requireConnectableSiteId(projectId);

        ShortLivedToken shortLived;
        LongLivedToken longLived;
        String accountName;
        try {
            shortLived = threadsApiClient.exchangeCode(
                    pending.clientId(), pending.clientSecret(), code, pending.redirectUri());
            longLived = threadsApiClient.exchangeLongLived(pending.clientSecret(), shortLived.accessToken());
            accountName = threadsApiClient.fetchUsername(longLived.accessToken());
        } catch (ThreadsApiException e) {
            throw new IllegalStateException("Threads との認可に失敗しました: " + e.getMessage(), e);
        }

        String stdin = configSetPayload(shortLived.userId(), longLived, accountName);
        String stdout;
        try {
            stdout = siteService.runLetsblogSns(siteId, "config-set", SNS, stdin);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "本番サイトへトークンを送れませんでした(接続失敗): " + e.getMessage(), e);
        }
        if (!STATUS_CONNECTED.equals(readConfigSetStatus(stdout))) {
            throw new IllegalStateException("本番サイトのプラグインが接続済みになりませんでした(接続失敗)");
        }
        return new XConnectResult(projectId, accountName);
    }

    /** Threads へのテスト投稿。失敗してもプラグインが履歴に残すので、理由を返すだけ。 */
    public XTestResult test(Long projectId) {
        return snsXService.test(projectId, SNS);
    }

    /** 切断。本番サイトのプラグインから Threads の設定(暗号化したトークンごと)を消す。 */
    public void disconnect(Long projectId) {
        Long siteId = snsXService.requireConnectableSiteId(projectId);
        try {
            siteService.runLetsblogSns(siteId, "config-clear", SNS, null);
        } catch (RuntimeException e) {
            throw new IllegalStateException("本番サイトの Threads の設定を削除できませんでした: " + e.getMessage(), e);
        }
    }

    private String configSetPayload(String userId, LongLivedToken token, String accountName) {
        long now = clock.instant().getEpochSecond();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sns", SNS);
        node.put("access_token", token.accessToken());
        node.put("user_id", userId);
        node.put("issued_at", now);
        node.put("expires_at", now + token.expiresIn());
        node.put("account_name", accountName);
        return node.toString();
    }

    private String readConfigSetStatus(String stdout) {
        try {
            return objectMapper.readTree(stdout).path("status").asText(null);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new IllegalStateException("本番サイトのプラグインの出力を解釈できません(接続失敗)", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
