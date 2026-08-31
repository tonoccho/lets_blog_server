package com.letsblog.content.contentcache;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

/**
 * コンテンツキャッシュAPIが外部へ出すリクエストの宛先を制限する(issue #902)。
 *
 * <p>{@code GET /api/content-cache?url=...} は利用者が指定したURLをサーバー側で取得する
 * (blogcard/amazon組み込みタグ、#147・#148・#149)。宛先の検査が無いと、有効なJWTを持つ
 * 利用者が content-service コンテナから到達できる任意のホストへリクエストを送らせ、
 * その結果を読み取れてしまう(docker network 内の {@code mysql} / {@code keycloak}、
 * ループバック、クラウドのメタデータエンドポイント {@code 169.254.169.254} 等)。
 *
 * <p>これは認可では解決しない。プロジェクトメンバーであっても同じことができるため、
 * <b>送信先の入力検証</b>で対処する。
 *
 * <p>名前解決の結果を検査するので、内部アドレスを返すDNS名も弾ける。ただし検査時と
 * ブラウザが実際に接続する時とで解決結果が変わる、いわゆるDNSリバインディングまでは
 * 塞げない(ブラウザ自身が名前解決を行うため)。{@code PlaywrightPageFetcher} は
 * <b>リダイレクトを含む各リクエストの直前</b>にこのガードを通すことで、その窓を狭めている。
 */
@Component
public class OutboundUrlGuard {

    /** テストから名前解決を差し替えるための継ぎ目。 */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final HostResolver resolver;

    public OutboundUrlGuard() {
        this(InetAddress::getAllByName);
    }

    OutboundUrlGuard(HostResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * URL文字列の宛先が許可されているか検査する。許可されない場合は
     * {@link ContentScrapingException} を投げる。
     *
     * <p>どの内部ホストへ到達したかは応答に載せない(存在の有無が探索に使えるため)。
     */
    public void requireAllowed(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ContentScrapingException("不正なURL形式です", e);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ContentScrapingException("ホスト名を含む絶対URLを指定してください", null);
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new ContentScrapingException("ホスト名を解決できませんでした", e);
        }
        if (addresses == null || addresses.length == 0) {
            throw new ContentScrapingException("ホスト名を解決できませんでした", null);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                // 解決先そのものは返さない。到達可否が内部構成の探索に使えるため。
                throw new ContentScrapingException("このURLは取得できません(外部の公開ページのみ取得できます)", null);
            }
        }
    }

    /** 例外を投げずに可否だけ返す({@code PlaywrightPageFetcher}のリクエスト遮断用)。 */
    public boolean isAllowed(String url) {
        try {
            requireAllowed(url);
            return true;
        } catch (ContentScrapingException e) {
            return false;
        }
    }

    /**
     * 外部の公開ページとして扱えないアドレスかどうか。
     *
     * <p>IPv6のユニークローカル({@code fc00::/7})は Java の {@code isSiteLocalAddress()} が
     * 拾わない(あちらが見るのは非推奨の {@code fec0::/10})ため、明示的に判定する。
     * 同様に、共有アドレス空間 {@code 100.64.0.0/10}(CGNAT)も標準APIの分類には無い。
     */
    static boolean isBlocked(InetAddress address) {
        if (address.isLoopbackAddress()          // 127.0.0.0/8、::1
                || address.isAnyLocalAddress()   // 0.0.0.0、::
                || address.isLinkLocalAddress()  // 169.254.0.0/16(クラウドメタデータ)、fe80::/10
                || address.isSiteLocalAddress()  // 10/8、172.16/12、192.168/16、fec0::/10
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            // fc00::/7 — IPv6ユニークローカル
            return (bytes[0] & 0xFE) == 0xFC;
        }
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 100.64.0.0/10 — 共有アドレス空間(CGNAT)
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            // 192.0.0.0/24(IETFプロトコル割当)、198.18.0.0/15(ベンチマーク用)
            if (first == 192 && second == 0 && (bytes[2] & 0xFF) == 0) {
                return true;
            }
            return first == 198 && (second == 18 || second == 19);
        }
        return false;
    }
}
