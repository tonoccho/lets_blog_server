package com.letsblog.project.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.project.client.HatenaApiClient;
import com.letsblog.project.client.HatenaApiClient.AccessToken;
import com.letsblog.project.client.HatenaApiClient.Profile;
import com.letsblog.project.client.HatenaApiClient.RequestToken;
import com.letsblog.project.client.HatenaApiException;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * プロジェクトのはてなブックマーク(公式アカウント)の接続・切断(issue #1582。Epic #1572)。X({@link SnsXService}、#1574)と同じ方式だが、
 * はてなは OAuth 1.0a(リクエストトークン → 認可 → アクセストークン)。
 *
 * <ul>
 *   <li>接続先は、プロジェクトの<b>本番サイト</b>の letsblog プラグイン。認可で得たアクセストークンとその秘密を、利用者が入力した
 *       consumer key / secret とともに、その場で {@code wp letsblog sns config set}(標準入力のJSON)でそのサイトへ送り、
 *       <b>アプリは保存しない・API で返さない</b>。プラグインがブックマークの投稿に署名するので consumer secret も要り、
 *       プラグインが暗号化して保存する。</li>
 *   <li>consumer secret・リクエストトークンとその秘密は認可の間だけメモリに持つ({@link HatenaAuthorizationStore})。
 *       OAuth 1.0a には state が無いので、callback の URL に state を載せて往復させる。</li>
 *   <li>はてなのアクセストークンは失効しないので更新しない。取り消された(API が 401)ときはプラグインが「要再接続」にする。</li>
 *   <li>状態・履歴・テスト投稿の読み取りは SNS に依らない処理なので {@link SnsXService} に SNS 名を渡して任せる。</li>
 * </ul>
 */
@Service
public class SnsHatenaService {

    private static final String SNS = "hatena";
    private static final String STATUS_CONNECTED = "接続済み";

    private final SnsXService snsXService;
    private final SiteService siteService;
    private final HatenaApiClient hatenaApiClient;
    private final HatenaAuthorizationStore store;
    private final CurrentActorService currentActorService;
    private final ObjectMapper objectMapper;

    @Autowired
    public SnsHatenaService(
            SnsXService snsXService,
            SiteService siteService,
            HatenaApiClient hatenaApiClient,
            HatenaAuthorizationStore store,
            CurrentActorService currentActorService,
            ObjectMapper objectMapper) {
        this.snsXService = snsXService;
        this.siteService = siteService;
        this.hatenaApiClient = hatenaApiClient;
        this.store = store;
        this.currentActorService = currentActorService;
        this.objectMapper = objectMapper;
    }

    /** 画面に出すはてなブックマークの接続状態と告知履歴。 */
    public XConnectionView view(Long projectId) {
        return snsXService.view(projectId, SNS);
    }

    /** 認可を始め、はてなの認可画面の URL を返す。接続できない状態では始めない。 */
    public String startAuthorization(Long projectId, String consumerKey, String consumerSecret, String redirectUri) {
        if (isBlank(consumerKey) || isBlank(consumerSecret) || isBlank(redirectUri)) {
            throw new IllegalArgumentException("Consumer Key・Consumer Secret・リダイレクト先は必須です");
        }
        Long siteId = snsXService.requireConnectableSiteId(projectId);
        String actorSub = currentActorService.getCurrentActorKeycloakSub();
        String state = store.newState(projectId);
        String callbackUrl = redirectUri + (redirectUri.contains("?") ? "&" : "?") + "state="
                + URLEncoder.encode(state, StandardCharsets.UTF_8);
        RequestToken requestToken;
        try {
            requestToken = hatenaApiClient.fetchRequestToken(consumerKey, consumerSecret, callbackUrl);
        } catch (HatenaApiException e) {
            throw new IllegalStateException("はてなとの認可に失敗しました: " + e.getMessage(), e);
        }
        store.create(state, projectId, siteId, actorSub, consumerKey, consumerSecret, requestToken.token(),
                requestToken.secret());
        return hatenaApiClient.authorizeUrl(requestToken.token());
    }

    /**
     * はてなの認可画面から戻ったときの処理。verifier をアクセストークンに交換し、アカウント名とともに本番サイトへ送る。
     * 認可を始めた本人・同じプロジェクト・同じリクエストトークンでなければ拒否する。
     */
    public XConnectResult completeAuthorization(Long projectId, String state, String oauthToken, String verifier) {
        String invalid = "認可の有効期限が切れたか、リクエストが不正です。もう一度接続してください";
        String actorSub = currentActorService.getCurrentActorKeycloakSub();
        HatenaAuthorizationStore.Pending pending = store.take(state)
                .filter(p -> p.projectId().equals(projectId))
                .filter(p -> p.actorSub() != null && p.actorSub().equals(actorSub))
                .filter(p -> p.requestToken().equals(oauthToken))
                .orElseThrow(() -> new IllegalArgumentException(invalid));
        Long siteId = snsXService.requireConnectableSiteId(projectId);

        AccessToken token;
        Profile profile;
        try {
            token = hatenaApiClient.fetchAccessToken(pending.consumerKey(), pending.consumerSecret(),
                    pending.requestToken(), pending.requestTokenSecret(), verifier);
            profile = hatenaApiClient.fetchProfile(pending.consumerKey(), pending.consumerSecret(), token.token(),
                    token.secret());
        } catch (HatenaApiException e) {
            throw new IllegalStateException("はてなとの認可に失敗しました: " + e.getMessage(), e);
        }

        String stdin = configSetPayload(pending, token, profile);
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

    /** はてなブックマークへのテスト投稿。失敗してもプラグインが履歴に残すので、理由を返すだけ。 */
    public XTestResult test(Long projectId) {
        return snsXService.test(projectId, SNS);
    }

    /** 切断。本番サイトのプラグインからはてなブックマークの設定(暗号化したトークンごと)を消す。 */
    public void disconnect(Long projectId) {
        Long siteId = snsXService.requireConnectableSiteId(projectId);
        try {
            siteService.runLetsblogSns(siteId, "config-clear", SNS, null);
        } catch (RuntimeException e) {
            throw new IllegalStateException("本番サイトのはてなブックマークの設定を削除できませんでした: " + e.getMessage(), e);
        }
    }

    private String configSetPayload(HatenaAuthorizationStore.Pending pending, AccessToken token, Profile profile) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sns", SNS);
        node.put("consumer_key", pending.consumerKey());
        node.put("consumer_secret", pending.consumerSecret());
        node.put("access_token", token.token());
        node.put("access_token_secret", token.secret());
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
