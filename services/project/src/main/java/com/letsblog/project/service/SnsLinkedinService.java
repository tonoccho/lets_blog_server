package com.letsblog.project.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.project.client.LinkedinApiClient;
import com.letsblog.project.client.LinkedinApiClient.AccessToken;
import com.letsblog.project.client.LinkedinApiClient.Profile;
import com.letsblog.project.client.LinkedinApiException;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * プロジェクトの LinkedIn(接続したメンバー本人のプロフィール)の接続・切断(issue #1581。Epic #1572)。
 * X({@link SnsXService}、#1574)と同じ方式。
 *
 * <ul>
 *   <li>接続先は、プロジェクトの<b>本番サイト</b>の letsblog プラグイン。認可で得た<b>アクセストークン</b>・メンバーID・
 *       有効期限をその場で {@code wp letsblog sns config set}(標準入力のJSON)でそのサイトへ送り、<b>アプリは保存しない・
 *       API で返さない</b>。Client Secret と認可の途中経過は認可の間だけメモリに持つ({@link XAuthorizationStore})。</li>
 *   <li>LinkedIn のトークンは60日で切れ、refresh token が出ないので更新しない。Client Secret はプラグインへ送らない。
 *       切れたら(またはプラグインが 401 を受けたら)プラグインが「要再接続」にする。</li>
 *   <li>状態・履歴・テスト投稿の読み取りは SNS に依らない処理なので {@link SnsXService} に SNS 名を渡して任せる。</li>
 * </ul>
 */
@Service
public class SnsLinkedinService {

    private static final String SNS = "linkedin";
    private static final String STATUS_CONNECTED = "接続済み";

    private final SnsXService snsXService;
    private final SiteService siteService;
    private final LinkedinApiClient linkedinApiClient;
    private final XAuthorizationStore store;
    private final CurrentActorService currentActorService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public SnsLinkedinService(
            SnsXService snsXService,
            SiteService siteService,
            LinkedinApiClient linkedinApiClient,
            XAuthorizationStore store,
            CurrentActorService currentActorService,
            ObjectMapper objectMapper) {
        this(snsXService, siteService, linkedinApiClient, store, currentActorService, objectMapper, Clock.systemUTC());
    }

    SnsLinkedinService(
            SnsXService snsXService,
            SiteService siteService,
            LinkedinApiClient linkedinApiClient,
            XAuthorizationStore store,
            CurrentActorService currentActorService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.snsXService = snsXService;
        this.siteService = siteService;
        this.linkedinApiClient = linkedinApiClient;
        this.store = store;
        this.currentActorService = currentActorService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 画面に出す LinkedIn の接続状態と告知履歴。 */
    public XConnectionView view(Long projectId) {
        return snsXService.view(projectId, SNS);
    }

    /** 認可を始め、LinkedIn の認可画面の URL を返す。接続できない状態では始めない。 */
    public String startAuthorization(Long projectId, String clientId, String clientSecret, String redirectUri) {
        if (isBlank(clientId) || isBlank(clientSecret) || isBlank(redirectUri)) {
            throw new IllegalArgumentException("Client ID・Client Secret・リダイレクト先は必須です");
        }
        Long siteId = snsXService.requireConnectableSiteId(projectId);
        String actorSub = currentActorService.getCurrentActorKeycloakSub();
        XAuthorizationStore.Pending pending =
                store.create(projectId, siteId, actorSub, clientId, clientSecret, redirectUri);
        return linkedinApiClient.authorizeUrl(clientId, redirectUri, pending.state());
    }

    /**
     * LinkedIn の認可画面から戻ったときの処理。認可コードをアクセストークンに交換し、userinfo のメンバーIDとともに本番サイトへ送る。
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

        AccessToken token;
        Profile profile;
        try {
            token = linkedinApiClient.exchangeCode(
                    pending.clientId(), pending.clientSecret(), code, pending.redirectUri());
            profile = linkedinApiClient.fetchProfile(token.accessToken());
        } catch (LinkedinApiException e) {
            throw new IllegalStateException("LinkedIn との認可に失敗しました: " + e.getMessage(), e);
        }

        String stdin = configSetPayload(token, profile);
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
        return new XConnectResult(projectId, profile.name());
    }

    /** LinkedIn へのテスト投稿。失敗してもプラグインが履歴に残すので、理由を返すだけ。 */
    public XTestResult test(Long projectId) {
        return snsXService.test(projectId, SNS);
    }

    /** 切断。本番サイトのプラグインから LinkedIn の設定(暗号化したトークンごと)を消す。 */
    public void disconnect(Long projectId) {
        Long siteId = snsXService.requireConnectableSiteId(projectId);
        try {
            siteService.runLetsblogSns(siteId, "config-clear", SNS, null);
        } catch (RuntimeException e) {
            throw new IllegalStateException("本番サイトの LinkedIn の設定を削除できませんでした: " + e.getMessage(), e);
        }
    }

    private String configSetPayload(AccessToken token, Profile profile) {
        long now = clock.instant().getEpochSecond();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sns", SNS);
        node.put("access_token", token.accessToken());
        node.put("member_id", profile.sub());
        node.put("expires_at", now + token.expiresIn());
        node.put("account_name", profile.name());
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
