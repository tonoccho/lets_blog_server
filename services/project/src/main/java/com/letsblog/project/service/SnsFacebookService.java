package com.letsblog.project.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.project.client.FacebookApiClient;
import com.letsblog.project.client.FacebookApiException;
import com.letsblog.project.dto.FacebookPageView;
import com.letsblog.project.dto.FacebookPagesView;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * プロジェクトの公式 Facebook ページの接続・切断(issue #1580。Epic #1572)。X({@link SnsXService}、#1574)と同じ方式で、
 * 個人アカウントには投稿しないため、認可のあとに投稿先のページを選ぶ段階が加わる。
 *
 * <ul>
 *   <li>認可({@link #completeAuthorization})では、コードをユーザートークン → 長期ユーザートークンに交換し、管理している
 *       ページ(ページのトークン付き)を取得して {@link FacebookPageSelectionStore} にメモリだけで持ち、ページの一覧(ID と名前)
 *       だけを返す。この時点では本番サイトへ何も送らない。</li>
 *   <li>ページを選ぶ({@link #selectPage})と、<b>選んだページのトークン</b>とページIDだけを、その場で
 *       {@code wp letsblog sns config set}(標準入力のJSON)で本番サイトのプラグインへ送る。アプリは保存しない・API で返さない。
 *       ユーザートークン・アプリのシークレット・選ばなかったページのトークンは送らない。</li>
 *   <li>状態・履歴・テスト投稿の読み取りは SNS に依らない処理なので {@link SnsXService} に SNS 名を渡して任せる。</li>
 * </ul>
 */
@Service
public class SnsFacebookService {

    private static final String SNS = "facebook";
    private static final String STATUS_CONNECTED = "接続済み";
    private static final String INVALID = "認可の有効期限が切れたか、リクエストが不正です。もう一度接続してください";

    private final SnsXService snsXService;
    private final SiteService siteService;
    private final FacebookApiClient facebookApiClient;
    private final XAuthorizationStore store;
    private final FacebookPageSelectionStore selectionStore;
    private final CurrentActorService currentActorService;
    private final ObjectMapper objectMapper;

    @Autowired
    public SnsFacebookService(
            SnsXService snsXService,
            SiteService siteService,
            FacebookApiClient facebookApiClient,
            XAuthorizationStore store,
            FacebookPageSelectionStore selectionStore,
            CurrentActorService currentActorService,
            ObjectMapper objectMapper) {
        this.snsXService = snsXService;
        this.siteService = siteService;
        this.facebookApiClient = facebookApiClient;
        this.store = store;
        this.selectionStore = selectionStore;
        this.currentActorService = currentActorService;
        this.objectMapper = objectMapper;
    }

    /** 画面に出す Facebook の接続状態と告知履歴。 */
    public XConnectionView view(Long projectId) {
        return snsXService.view(projectId, SNS);
    }

    /** 認可を始め、Facebook の認可画面の URL を返す。接続できない状態では始めない。 */
    public String startAuthorization(Long projectId, String clientId, String clientSecret, String redirectUri) {
        if (isBlank(clientId) || isBlank(clientSecret) || isBlank(redirectUri)) {
            throw new IllegalArgumentException("アプリID・アプリシークレット・リダイレクト先は必須です");
        }
        Long siteId = snsXService.requireConnectableSiteId(projectId);
        String actorSub = currentActorService.getCurrentActorKeycloakSub();
        XAuthorizationStore.Pending pending =
                store.create(projectId, siteId, actorSub, clientId, clientSecret, redirectUri);
        return facebookApiClient.authorizeUrl(clientId, redirectUri, pending.state());
    }

    /**
     * Facebook の認可画面から戻ったときの処理。認可コードを(長期)ユーザートークンに交換し、管理しているページを取得して
     * 選択待ちとして保存し、選べるページの一覧を返す。認可を始めた本人・同じプロジェクトでなければ拒否する。
     */
    public FacebookPagesView completeAuthorization(Long projectId, String state, String code) {
        String actorSub = currentActorService.getCurrentActorKeycloakSub();
        XAuthorizationStore.Pending pending = store.take(state)
                .filter(p -> p.projectId().equals(projectId))
                .filter(p -> p.actorSub() != null && p.actorSub().equals(actorSub))
                .orElseThrow(() -> new IllegalArgumentException(INVALID));
        Long siteId = snsXService.requireConnectableSiteId(projectId);

        List<FacebookApiClient.Page> pages;
        try {
            String shortLived = facebookApiClient.exchangeCode(
                    pending.clientId(), pending.clientSecret(), code, pending.redirectUri());
            String longLived = facebookApiClient.exchangeLongLived(pending.clientId(), pending.clientSecret(), shortLived);
            pages = facebookApiClient.listPages(longLived);
        } catch (FacebookApiException e) {
            throw new IllegalStateException("Facebook との認可に失敗しました: " + e.getMessage(), e);
        }
        if (pages.isEmpty()) {
            throw new IllegalStateException(
                    "接続できる Facebook ページがありません(管理しているページが無いか、ページの権限が許可されていません)");
        }
        selectionStore.save(state, projectId, siteId, actorSub, pages);
        return view(projectId, pages);
    }

    /** 選べるページの一覧(認可を始めた本人・同じプロジェクトだけ)。トークンは返さない。 */
    public FacebookPagesView pages(Long projectId, String state) {
        FacebookPageSelectionStore.Selection selection = requireSelection(projectId, state);
        return view(projectId, selection.pages());
    }

    /** 選んだページのトークンだけを本番サイトへ送り、ページ名を返す。 */
    public XConnectResult selectPage(Long projectId, String state, String pageId) {
        FacebookPageSelectionStore.Selection selection = requireSelection(projectId, state);
        Optional<FacebookApiClient.Page> chosen =
                selection.pages().stream().filter(p -> p.id().equals(pageId)).findFirst();
        FacebookApiClient.Page page = chosen.orElseThrow(
                () -> new IllegalArgumentException("選んだページが、管理しているページの中に見つかりません。もう一度接続してください"));

        String stdin = configSetPayload(page);
        String stdout;
        try {
            stdout = siteService.runLetsblogSns(selection.siteId(), "config-set", SNS, stdin);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "本番サイトへトークンを送れませんでした(接続失敗): " + e.getMessage(), e);
        }
        if (!STATUS_CONNECTED.equals(readConfigSetStatus(stdout))) {
            throw new IllegalStateException("本番サイトのプラグインが接続済みになりませんでした(接続失敗)");
        }
        selectionStore.remove(state);
        return new XConnectResult(projectId, page.name());
    }

    /** Facebook へのテスト投稿。失敗してもプラグインが履歴に残すので、理由を返すだけ。 */
    public XTestResult test(Long projectId) {
        return snsXService.test(projectId, SNS);
    }

    /** 切断。本番サイトのプラグインから Facebook の設定(暗号化したトークンごと)を消す。 */
    public void disconnect(Long projectId) {
        Long siteId = snsXService.requireConnectableSiteId(projectId);
        try {
            siteService.runLetsblogSns(siteId, "config-clear", SNS, null);
        } catch (RuntimeException e) {
            throw new IllegalStateException("本番サイトの Facebook の設定を削除できませんでした: " + e.getMessage(), e);
        }
    }

    private FacebookPageSelectionStore.Selection requireSelection(Long projectId, String state) {
        FacebookPageSelectionStore.Selection selection = selectionStore.find(state)
                .filter(s -> s.projectId().equals(projectId))
                .orElseThrow(() -> new IllegalArgumentException(INVALID));
        String actorSub = currentActorService.getCurrentActorKeycloakSub();
        if (selection.actorSub() == null || !selection.actorSub().equals(actorSub)) {
            throw new IllegalArgumentException(INVALID);
        }
        return selection;
    }

    private static FacebookPagesView view(Long projectId, List<FacebookApiClient.Page> pages) {
        return new FacebookPagesView(
                projectId, pages.stream().map(p -> new FacebookPageView(p.id(), p.name())).toList());
    }

    private String configSetPayload(FacebookApiClient.Page page) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sns", SNS);
        node.put("access_token", page.accessToken());
        node.put("page_id", page.id());
        node.put("account_name", page.name());
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
